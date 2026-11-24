package com.mytv.cn.network

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

internal sealed interface ProtoValue {
    data class VarInt(val value: Long) : ProtoValue
    data class Fixed32(val value: Int) : ProtoValue
    data class Fixed64(val value: Long) : ProtoValue
    data class Bytes(val value: ByteArray) : ProtoValue
}

internal class ProtoMessage(private val fields: Map<Int, List<ProtoValue>>) {
    fun vars(field: Int): List<Long> = fields[field].orEmpty().mapNotNull { (it as? ProtoValue.VarInt)?.value }
    fun bytes(field: Int): List<ByteArray> = fields[field].orEmpty().mapNotNull { (it as? ProtoValue.Bytes)?.value }
    fun string(field: Int): String = bytes(field).firstOrNull()?.toString(Charsets.UTF_8).orEmpty()
    fun allBytes(): List<ByteArray> = fields.values.flatten().mapNotNull { (it as? ProtoValue.Bytes)?.value }
    fun allVars(): List<Long> = fields.values.flatten().mapNotNull { (it as? ProtoValue.VarInt)?.value }
}

internal object ProtoWire {
    fun parse(input: ByteArray): ProtoMessage {
        val fields = LinkedHashMap<Int, MutableList<ProtoValue>>()
        var index = 0
        while (index < input.size) {
            val (tag, tagEnd) = readVarInt(input, index) ?: break
            index = tagEnd
            val field = (tag ushr 3).toInt()
            if (field <= 0) break
            val value: ProtoValue = when ((tag and 7).toInt()) {
                0 -> {
                    val pair = readVarInt(input, index) ?: break
                    index = pair.second
                    ProtoValue.VarInt(pair.first)
                }
                1 -> {
                    if (index + 8 > input.size) break
                    var result = 0L
                    repeat(8) { offset -> result = result or ((input[index + offset].toLong() and 0xff) shl (offset * 8)) }
                    index += 8
                    ProtoValue.Fixed64(result)
                }
                2 -> {
                    val lengthPair = readVarInt(input, index) ?: break
                    index = lengthPair.second
                    val length = lengthPair.first.toInt()
                    if (length < 0 || index + length > input.size) break
                    ProtoValue.Bytes(input.copyOfRange(index, index + length)).also { index += length }
                }
                5 -> {
                    if (index + 4 > input.size) break
                    val result = (input[index].toInt() and 0xff) or
                        ((input[index + 1].toInt() and 0xff) shl 8) or
                        ((input[index + 2].toInt() and 0xff) shl 16) or
                        ((input[index + 3].toInt() and 0xff) shl 24)
                    index += 4
                    ProtoValue.Fixed32(result)
                }
                else -> break
            }
            fields.getOrPut(field) { ArrayList() }.add(value)
        }
        return ProtoMessage(fields)
    }

    fun ungzip(input: ByteArray): ByteArray {
        if (input.size < 2 || input[0] != 0x1f.toByte() || input[1] != 0x8b.toByte()) return input
        return GZIPInputStream(ByteArrayInputStream(input)).use { stream ->
            val output = ByteArrayOutputStream()
            stream.copyTo(output)
            output.toByteArray()
        }
    }

    private fun readVarInt(input: ByteArray, start: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var index = start
        while (index < input.size && shift < 64) {
            val value = input[index++].toInt() and 0xff
            result = result or ((value and 0x7f).toLong() shl shift)
            if (value and 0x80 == 0) return result to index
            shift += 7
        }
        return null
    }
}
