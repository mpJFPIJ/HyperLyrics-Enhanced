/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.central

import kotlinx.serialization.json.Json
import io.github.proify.extensions.inflate as boundedInflate
import java.util.zip.DataFormatException

internal val json: Json = Json {
    coerceInputValues = true     // 尝试转换类型
    ignoreUnknownKeys = true     // 忽略未知字段
    isLenient = true             // 宽松的 JSON 语法
    explicitNulls = false        // 不序列化 null
    encodeDefaults = false       // 不序列化默认值
}

/**
 * 使用 Deflate 算法解压当前字节数组。
 *
 * @return 解压后的字节数组
 * @throws DataFormatException 当压缩数据格式非法时抛出
 */
@Throws(DataFormatException::class)
internal fun ByteArray.inflate(): ByteArray {
    return this.boundedInflate()
}
