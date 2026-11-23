package top.imsyy.splayer_next.android.bridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import org.json.JSONObject
import top.imsyy.splayer_next.android.AppPaths
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * SAF 文档读写，picker 选中的所有 content uri 都从这里出入
 */
object DocumentIo {

    /** 文本读取上限，超过直接报错而不是把整块内容塞进内存 */
    private const val MAX_TEXT_BYTES = 8 * 1024 * 1024

    /** 二进制读取上限 */
    private const val MAX_BINARY_BYTES = 64 * 1024 * 1024

    /** 缩略图 JPEG 质量 */
    private const val THUMB_QUALITY = 85

    /** 允许写入的缓存桶名，挡掉目录穿越 */
    private val BUCKET_PATTERN = Regex("[a-z0-9][a-z0-9-]*")

    /** MIME 与扩展名对照，给复制进缓存桶的文件命名 */
    private val EXT_BY_MIME = mapOf(
        "image/jpeg" to "jpg",
        "image/png" to "png",
        "image/webp" to "webp",
        "image/gif" to "gif",
        "image/bmp" to "bmp",
    )

    /**
     * 查询文档基础信息
     * @param context - 上下文
     * @param uri - 文档 uri
     * @returns path / name / size / mimeType / isDirectory
     */
    fun stat(context: Context, uri: Uri): JSONObject {
        val mime = context.contentResolver.getType(uri) ?: ""
        return JSONObject()
            .put("path", uri.toString())
            .put("name", displayName(context, uri) ?: uri.lastPathSegment.orEmpty())
            .put("size", sizeOf(context, uri) ?: 0L)
            .put("mimeType", mime)
            .put("isDirectory", mime == "vnd.android.document/directory")
    }

    /**
     * 以 UTF-8 读取文本
     * @param context - 上下文
     * @param uri - 文档 uri
     * @returns 文本内容，BOM 已去除
     */
    fun readText(context: Context, uri: Uri): String {
        val text = String(readBytes(context, uri, MAX_TEXT_BYTES), Charsets.UTF_8)
        return if (text.startsWith("\uFEFF")) text.substring(1) else text
    }

    /**
     * 读取二进制并编码成 base64
     * @param context - 上下文
     * @param uri - 文档 uri
     * @returns 无换行 base64
     */
    fun readBase64(context: Context, uri: Uri): String =
        Base64.encodeToString(readBytes(context, uri, MAX_BINARY_BYTES), Base64.NO_WRAP)

    /**
     * 覆盖写入文本
     * @param context - 上下文
     * @param uri - 文档 uri
     * @param text - 文本内容
     */
    fun writeText(context: Context, uri: Uri, text: String) {
        val resolver = context.contentResolver
        // 只有 "wt" 会截断旧内容，个别 provider 不支持时退回普通写
        val stream = resolver.openOutputStream(uri, "wt") ?: resolver.openOutputStream(uri)
            ?: throw IllegalStateException("无法写入文件: " + uri)
        stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    /**
     * 覆盖写入 base64 二进制
     * @param context - 上下文
     * @param uri - 文档 uri
     * @param base64 - 无换行 base64
     */
    fun writeBase64(context: Context, uri: Uri, base64: String) {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        val resolver = context.contentResolver
        val stream = resolver.openOutputStream(uri, "wt") ?: resolver.openOutputStream(uri)
            ?: throw IllegalStateException("无法写入文件: " + uri)
        stream.use { it.write(bytes) }
    }

    /**
     * 生成最长边不超过 maxSize 的 JPEG 缩略图
     * @param context - 上下文
     * @param uri - 图片 uri
     * @param maxSize - 目标最长边像素
     * @returns mime / base64 / width / height
     */
    fun thumbnailBase64(context: Context, uri: Uri, maxSize: Int): JSONObject {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openImage(context, uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalStateException("无法解码图片: " + uri)
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSizeOf(bounds, maxSize) }
        val decoded = openImage(context, uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IllegalStateException("无法解码图片: " + uri)
        val scaled = scaleToMax(decoded, maxSize)
        val out = ByteArrayOutputStream()
        try {
            scaled.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, out)
            return JSONObject()
                .put("mime", "image/jpeg")
                .put("width", scaled.width)
                .put("height", scaled.height)
                .put("base64", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
        } finally {
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
        }
    }

    /**
     * 把文档复制到应用缓存目录下的桶，文件名取内容 sha1 前 16 位
     * @param context - 上下文
     * @param uri - 源文档 uri
     * @param bucket - 桶名，仅允许小写字母、数字与连字符
     * @returns 落盘文件名
     */
    fun copyToBucket(context: Context, uri: Uri, bucket: String): String {
        val dir = bucketDir(bucket)
        val bytes = readBytes(context, uri, MAX_BINARY_BYTES)
        val name = hashOf(bytes) + "." + extensionOf(context, uri)
        dir.mkdirs()
        File(dir, name).writeBytes(bytes)
        return name
    }

    /**
     * 清空缓存桶
     * @param bucket - 桶名
     */
    fun clearBucket(bucket: String) {
        runCatching { bucketDir(bucket).deleteRecursively() }
    }

    /**
     * 解析并校验桶目录
     * @param bucket - 桶名
     * @returns 缓存桶目录
     */
    private fun bucketDir(bucket: String): File {
        if (!BUCKET_PATTERN.matches(bucket)) throw IllegalArgumentException("非法缓存桶: " + bucket)
        return File(AppPaths.cacheDir, bucket)
    }

    /**
     * 读取全部字节，超过上限直接失败
     * @param context - 上下文
     * @param uri - 文档 uri
     * @param limit - 允许的最大字节数
     * @returns 原始字节
     */
    private fun readBytes(context: Context, uri: Uri, limit: Int): ByteArray {
        val size = sizeOf(context, uri)
        if (size != null && size > limit) {
            throw IllegalStateException("文件超过读取上限: " + size + " bytes")
        }
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("无法读取文件: " + uri)
        return stream.use { readLimited(it, limit) }
    }

    /**
     * 逐块读取并限制总量，size 未知时也不会撑爆内存
     * @param stream - 输入流
     * @param limit - 最大字节数
     * @returns 原始字节
     */
    private fun readLimited(stream: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > limit) {
                throw IllegalStateException("文件超过读取上限: " + limit + " bytes")
            }
        }
        return out.toByteArray()
    }

    /**
     * 读取显示名
     * @param context - 上下文
     * @param uri - 文档 uri
     * @returns 显示名，取不到为 null
     */
    private fun displayName(context: Context, uri: Uri): String? =
        runCatching {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * 读取文档大小
     * @param context - 上下文
     * @param uri - 文档 uri
     * @returns 字节数，未知为 null
     */
    private fun sizeOf(context: Context, uri: Uri): Long? =
        runCatching {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
                }
        }.getOrNull()

    /**
     * 打开图片输入流
     * @param context - 上下文
     * @param uri - 图片 uri
     * @returns 输入流，打不开为 null
     */
    private fun openImage(context: Context, uri: Uri): InputStream? =
        runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()

    /**
     * 按最长边计算采样率，先粗采样再精确缩放，避免大图整张进内存
     * @param bounds - 只含尺寸的解码选项
     * @param maxSize - 目标最长边
     * @returns 2 的幂采样率
     */
    private fun sampleSizeOf(bounds: BitmapFactory.Options, maxSize: Int): Int {
        val target = if (maxSize > 0) maxSize else 300
        var sample = 1
        while (
            bounds.outWidth / (sample * 2) >= target || bounds.outHeight / (sample * 2) >= target
        ) {
            sample *= 2
        }
        return sample
    }

    /**
     * 等比缩放到最长边不超过 maxSize
     * @param bitmap - 解码结果
     * @param maxSize - 目标最长边
     * @returns 缩放后的位图，未超限时返回原图
     */
    private fun scaleToMax(bitmap: Bitmap, maxSize: Int): Bitmap {
        val target = if (maxSize > 0) maxSize else 300
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= target) return bitmap
        val ratio = target.toFloat() / longest.toFloat()
        val width = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    /**
     * 文档扩展名，优先取显示名，其次按 MIME 映射
     * @param context - 上下文
     * @param uri - 文档 uri
     * @returns 扩展名，无法判断时用 bin
     */
    private fun extensionOf(context: Context, uri: Uri): String {
        val fromName = displayName(context, uri)
            ?.substringAfterLast('.', "")
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() && it.length <= 5 && it.all { ch -> ch.isLetterOrDigit() } }
        if (fromName != null) return fromName
        val mime = context.contentResolver.getType(uri)?.lowercase().orEmpty()
        return EXT_BY_MIME[mime] ?: "bin"
    }

    /**
     * 内容 sha1，取前 16 位当文件名
     * @param bytes - 原始字节
     * @returns 16 位十六进制文件名
     */
    private fun hashOf(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(bytes)
        val hexChars = "0123456789abcdef"
        val hex = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xff
            hex.append(hexChars[value ushr 4]).append(hexChars[value and 0x0f])
        }
        return hex.substring(0, 16)
    }
}
