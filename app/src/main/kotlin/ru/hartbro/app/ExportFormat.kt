package ru.hartbro.app

import ru.hartbro.core.SessionXlsx

internal enum class ExportFormat(val extension: String, val mime: String) {
    XLSX("xlsx", SessionXlsx.MIME), PDF("pdf", "application/pdf"), CSV("csv", "text/csv")
}
