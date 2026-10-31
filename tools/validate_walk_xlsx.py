#!/usr/bin/env python3
"""Independent OOXML reader check; expected TSV is synthetic, explicitly not a field capture."""
import argparse
import csv
import json
from pathlib import Path
import openpyxl

parser = argparse.ArgumentParser()
parser.add_argument("directory", type=Path)
args = parser.parse_args()
path = args.directory / "TEST-ONLY-walk-100-devices.xlsx"
with (args.directory / "TEST-ONLY-walk-100-devices.expected.tsv").open() as stream:
    expected = list(csv.reader(stream, delimiter="\t"))
book = openpyxl.load_workbook(path, data_only=False)
assert book.sheetnames == ["Приборы"], book.sheetnames
sheet = book.active
assert sheet.max_column == 5 and sheet.max_row == 101
assert sheet.freeze_panes == "A2"
assert len(sheet.tables) == 1
assert sheet.tables["WalkDevices"].autoFilter.ref == "A1:E101"
assert sheet.sheet_state == "visible"
assert not any(column.hidden for column in sheet.column_dimensions.values())
for row_number, row in enumerate(expected, 1):
    for column_number, wanted in enumerate(row, 1):
        cell = sheet.cell(row_number, column_number)
        assert (cell.value or "") == wanted, (cell.coordinate, cell.value, wanted)
        if wanted:
            assert cell.data_type == "s", (cell.coordinate, cell.data_type)
            assert cell.data_type != "f"
assert any(cell.value and cell.value.startswith("00") for cell in list(sheet.columns)[1][1:])
assert any(cell.value == "07.2025" for cell in list(sheet.columns)[2][1:])
assert any(cell.value == "2025" for cell in list(sheet.columns)[2][1:])
result = {"file": path.name, "synthetic_data_only": True, "sheets": book.sheetnames,
          "columns": 5, "devices": 100, "compared_cells": 505,
          "freeze": sheet.freeze_panes, "filter": sheet.tables["WalkDevices"].autoFilter.ref,
          "serial_leading_zeros_preserved": True, "date_precision_preserved": True}
(args.directory / "walk-xlsx-validation.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
print(json.dumps(result, ensure_ascii=False))
