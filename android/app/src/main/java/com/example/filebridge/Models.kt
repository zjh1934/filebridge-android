package com.example.filebridge

/**
 * 界面与 GitHub 中继后端共用的数据模型。
 *
 * v1.3 瘦身时把 Api.kt 连同 Retrofit 接口一起删了，误删了这几个类；
 * 现在单独拎出来，只保留纯 Kotlin data class，不引入任何网络库依赖。
 */

data class FileItem(
    val id: String = "",
    val name: String = "",
    val parent: String = "",
    val rel: String = "",
    val type: String = "file",
    val size: Long = 0,
    val sha256: String = "",
    val mime: String = "",
    val updated_at: Double = 0.0,
)

data class ListResp(
    val parent: String = "",
    val dirs: List<FileItem> = emptyList(),
    val files: List<FileItem> = emptyList(),
)

data class TrashItem(
    val id: String = "",
    val name: String = "",
    val parent: String = "",
    val rel: String = "",
    val type: String = "file",
    val deleted_at: Double? = null,
)
