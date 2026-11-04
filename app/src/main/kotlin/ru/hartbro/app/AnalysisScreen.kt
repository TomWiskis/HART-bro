package ru.hartbro.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import ru.hartbro.app.session.*
import ru.hartbro.core.*

@Composable internal fun AnalysisDialog(state:SessionState,c:SessionController,importHex:(Boolean)->Unit,export:()->Unit,connect:()->Unit) {
    if(!state.analysisVisible)return
    val analysis=state.analysis
    ActionDialog("Прослушивание HART / анализ HEX",{c.showAnalysis(false)},"hartAnalysis",content={
        Text("Аппаратная видимость чужого обмена зависит от модема и его режима. Для Android и конкретного экземпляра физическая проверка пока не выполнена. Приём без запросов ничего не передаёт в HART-линию.")
        Text("Для ЭнИ-406 пассивный режим не заявлен в проверенном руководстве. У VIATOR описан приём обмена другого master через Windows VSPCF; это не подтверждает Android-режим конкретного модема.",style=MaterialTheme.typography.bodySmall)
        if(state.captureActive) {
            Text("Приём: ${state.capture.phase}; запросов видно ${state.capture.requestFrames}; ответов/бурстов ${state.capture.responseFrames}; собственных HART TX: 0",modifier=Modifier.testTag("captureStatus"))
            Text("Ноль принятых кадров означает отсутствие наблюдений, а не доказательство отсутствия аппаратной поддержки.")
            Button(onClick=c::stopCapture,modifier=Modifier.testTag("stopPassiveCapture")){Text("Остановить приём")}
        } else OutlinedButton(onClick=connect,enabled=!state.active && !state.rangeOwned && !state.editBusy,modifier=Modifier.testTag("preparePassiveCapture")){Text("Выбрать модем для приёма без запросов…")}
        Row {OutlinedButton(onClick={importHex(false)},enabled=!state.captureActive,modifier=Modifier.testTag("importHartHex")){Text("Импорт HEX")};TextButton(onClick={importHex(true)}){Text("Журнал до")}}
        if(analysis!=null) {
            Text("Источник: ${analysis.source}")
            Text("Корректных кадров: ${analysis.valid}; отклонений декодера: ${analysis.records.size-analysis.valid}; известных запросов записи: ${analysis.writes.size}",modifier=Modifier.testTag("analysisCounts"))
            analysis.notes.forEach{Text(it,style=MaterialTheme.typography.bodySmall)}
            val pairs=analysis.pairs()
            Text("Пар запрос/ответ: ${pairs.size}. HART не содержит transaction ID; совпадение адреса/команды не доказывает выполнение операции.")
            if(pairs.any{it.ambiguous})Text("Есть несколько одинаковых запросов перед ответом: сопоставление неоднозначно.")
            Text("Неизвестная команда не объявляется установкой нуля. Автоматического воспроизведения пакетов нет.")
            state.analysisBaseline?.let{before->Text("Сравнение с: ${before.source}");analysis.compare(before).take(100).forEach{Text(it,fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall)}}
            Row {TextButton(onClick=c::saveAnalysisSnapshot,modifier=Modifier.testTag("saveHartScenario")){Text("Сохранить сценарий")};TextButton(onClick=export){Text("Экспорт TXT")}}
            Text("Экран: последние 100 событий; экспорт содержит полный исходный файл / анализ.")
            analysis.records.takeLast(100).forEach{item->
                Text("${item.receivedAt ?: "Время неизвестно"} · ${item.summary}",color=if(!item.valid||item.effect==CommandEffect.WRITE)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,style=MaterialTheme.typography.bodySmall)
                Text(item.raw.hex(),fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall)
                HorizontalDivider()
            }
        }
        if(state.analysisFiles.isNotEmpty())Text("Сохранённые сценарии")
        state.analysisFiles.take(10).forEach{name->TextButton(onClick={c.openAnalysisFile(name)},enabled=!state.captureActive){Text(name)}}
    },actions={TextButton(onClick={c.showAnalysis(false)},modifier=Modifier.fillMaxWidth()){Text("Закрыть")}})
}
