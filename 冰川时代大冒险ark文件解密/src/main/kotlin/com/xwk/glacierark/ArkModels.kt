package com.xwk.glacierark

data class ArkEntry(
    val name: String,
    val offset: Long,
    val orig: Long,
    val comp: Long,
    val stored: Long,
    val tag: String,
    val hash: String,
    var encrypted: Boolean = false,
    var md5Plain: String = ""
)

data class ArkHeader(
    val count: Int,
    val dirOffset: Long,
    val version: String
)