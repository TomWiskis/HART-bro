#!/usr/bin/env python3
"""Independent OOXML-reader check against the supplied 0.3.0 CSV. Requires openpyxl only for this check."""
import argparse
import csv
import datetime as dt
import json
import struct
import warnings
from pathlib import Path
import openpyxl

parser = argparse.ArgumentParser()
parser.add_argument('xlsx', type=Path)
parser.add_argument('--source', type=Path, default=Path('hart-core/src/test/resources/export/session-148d023e.csv'))
parser.add_argument('--output', type=Path)
args = parser.parse_args()
with args.source.open(encoding='utf-8-sig', newline='') as stream:
    source = list(csv.DictReader(stream, delimiter=';'))
with warnings.catch_warnings(record=True) as notices:
    warnings.simplefilter('always')
    book = openpyxl.load_workbook(args.xlsx, data_only=False)
assert len(book.worksheets) == 1
sheet = book.active
reference = sheet.tables['Measurements'].ref
first = int(''.join(c for c in reference.split(':')[0] if c.isdigit()))
last = int(''.join(c for c in reference.split(':')[1] if c.isdigit()))
headers = {sheet.cell(first, c).value: c for c in range(1, sheet.max_column + 1)}
rows = {}
for index in range(first + 1, last + 1):
    timestamp = sheet.cell(index, 1).value
    assert isinstance(timestamp, dt.datetime), 'Receive time must be an Excel datetime'
    timestamp = timestamp.replace(tzinfo=dt.timezone.utc)
    assert timestamp not in rows, 'Duplicate timestamp row'
    rows[timestamp] = index
assert len(source) == 269 and len(rows) == 68
values = sum(sheet.cell(row, column).value is not None for row in rows.values() for column in range(2, 6))
assert values == 269
for record in source:
    row = rows[dt.datetime.fromisoformat(record['timestamp_utc'].replace('Z', '+00:00'))]
    slot = record['variable']
    value = sheet.cell(row, ['PV', 'SV', 'TV', 'QV'].index(slot) + 2)
    assert value.data_type == 'n', 'Measurements must be numeric'
    assert value.number_format == 'General', 'Very small values must not display as rounded zero'
    assert struct.pack('!f', value.value) == struct.pack('!f', float(record['value']))
    assert sheet.cell(row, headers[f'{slot} — качество']).value == record['quality']
    for field, label in [('command', 'команда HART'), ('hart_unit_code', 'код единицы HART'),
                         ('variable_code', 'код переменной HART'), ('raw_status', 'исходный статус переменной'),
                         ('device_status', 'статус прибора')]:
        expected = int(record[field]) if record[field] else None
        assert sheet.cell(row, headers[f'{slot} — {label}']).value == expected, (record['timestamp_utc'], slot, field)
assert all(sheet.cell(first + 1, c).value is None for c in (3, 4, 5))
assert sheet['B6'].value == 'Нет данных' and sheet['C6'].value == 'Нет данных'
assert sheet['F6'].value == '2662-BF2C34'
assert sheet.freeze_panes == f'A{first + 1}'
assert all(sheet.tables[name].autoFilter.ref == sheet.tables[name].ref for name in sheet.tables)
assert not notices, [str(item.message) for item in notices]
result = dict(reader='openpyxl', sheets=book.sheetnames, sourceValues=269, timeRows=68, exportedNumericValues=values,
              freezePanes=sheet.freeze_panes, tables={name: sheet.tables[name].ref for name in sheet.tables},
              allValuesRoundTripAsFloat32=True, numericDates=True, allQualityAndSourceFieldsMatch=True,
              missingVariablesBlank=True, factoryFactsNotInvented=True, warnings=[])
text = json.dumps(result, ensure_ascii=False, indent=2)
print(text)
if args.output:
    args.output.write_text(text + '\n')
