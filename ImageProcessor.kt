package com.example.enhancedcam

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfInt
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import java.io.Closeable
import kotlin.math.abs

/** Tracks native Mats so they are always released, even on cancellation/exception. */
internal class MatScope : Closeable {
    private val mats = ArrayList<Mat>()
    fun mat(): Mat = Mat().also { mats.add(it) }
    fun <T : Mat> keep(m: T): T = m.also { mats.add(it) }
    override fun close() {
        mats.forEach { it.release() }
        mats.clear()
    }
}

object ImageProcessor {

    /** Pixel-difference (0-255, after blur) at which a pixel is fully rejected as "moving". */
    private const val GHOST_THRESHOLD = 32.0

    /** Highlight protection: L below ~55% gets full CLAHE, fades to zero at ~95%. */
    private const val CLAHE_CLIP = 2.0

    /**
     * Full pipeline: decode -> pick sharpest reference -> ECC align -> ghost-aware
     * weighted average -> rotate upright -> shadow-focused CLAHE -> light sharpen -> JPEG.
     * Blocking / CPU heavy: call from Dispatchers.Default.
     */
    suspend fun processBurst(
        jpegs: List<ByteArray>,
        rotationDegrees: Int,
        jpegQuality: Int = 95
    ): ByteArray {
        require(jpegs.isNotEmpty()) { "No frames" }
        // Cap OpenCV's internal thread pool: keeps UI smooth and the SoC cooler.
        Core.setNumThreads(3)

        MatScope().use { s ->
            val stacked = s.keep(stackFrames(jpegs))
            val upright = s.keep(rotate(stacked, rotationDegrees))
            stacked.release()
            currentCoroutineContext().ensureActive()

            val toned = s.mat()
            enhanceTone(upright, toned, CLAHE_CLIP)
            upright.release()

            sharpen(toned, amount = 0.45, sigma = 1.2)

            val buf = MatOfByte()
            val params = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, jpegQuality)
            val ok = Imgcodecs.imencode(".jpg", toned, buf, params)
            params.release()
            if (!ok) {
                buf.release()
                throw IllegalStateException("JPEG encode failed")
            }
            val bytes = buf.toArray()
            buf.release()
            return bytes
        }
    }

    // ---------------------------------------------------------------- stacking

    private suspend fun stackFrames(jpegs: List<ByteArray>): Mat {
        if (jpegs.size == 1) return decodeFull(jpegs[0])

        MatScope().use { s ->
            // 1/4-size grayscale decodes are cheap and used for sharpness + alignment.
            val smalls = jpegs.map { s.keep(decodeSmallGray(it)) }
            val refIdx = smalls.indices.maxByOrNull { sharpness(smalls[it]) } ?: 0

            val ref = s.keep(decodeFull(jpegs[refIdx]))
            val refSmall = smalls[refIdx]
            val scale = ref.cols().toDouble() / refSmall.cols().toDouble()

            val acc = s.mat()
            ref.convertTo(acc, CvType.CV_32FC3)
            val wsum = s.keep(Mat(ref.size(), CvType.CV_32FC1, Scalar(1.0)))

            val refBlur = s.mat()
            Imgproc.cvtColor(ref, refBlur, Imgproc.COLOR_BGR2GRAY)
            Imgproc.GaussianBlur(refBlur, refBlur, Size(5.0, 5.0), 0.0)

            for (i in jpegs.indices) {
                if (i == refIdx) continue
                currentCoroutineContext().ensureActive()

                MatScope().use { fs ->
                    val frame = fs.keep(decodeFull(jpegs[i]))
                    val aligned = fs.mat()
                    val warp = estimateWarp(refSmall, smalls[i], scale)
                    if (warp != null) {
                        fs.keep(warp)
                        Imgproc.warpAffine(
                            frame, aligned, warp, frame.size(),
                            Imgproc.INTER_LINEAR or Imgproc.WARP_INVERSE_MAP,
                            Core.BORDER_REFLECT, Scalar.all(0.0)
                        )
                    } else {
                        frame.copyTo(aligned)
                    }
                    frame.release()

                    // Per-pixel ghost rejection weight: 1 where frames agree, 0 where they don't.
                    val g = fs.mat()
                    Imgproc.cvtColor(aligned, g, Imgproc.COLOR_BGR2GRAY)
                    Imgproc.GaussianBlur(g, g, Size(5.0, 5.0), 0.0)
                    val diff = fs.mat()
                    Core.absdiff(refBlur, g, diff)
                    val w = fs.mat()
                    diff.convertTo(w, CvType.CV_32F, -1.0 / GHOST_THRESHOLD, 1.0)
                    Core.max(w, Scalar(0.0), w)

                    val f32 = fs.mat()
                    aligned.convertTo(f32, CvType.CV_32FC3)
                    val w3 = fs.mat()
                    Core.merge(listOf(w, w, w), w3)
                    Core.multiply(f32, w3, f32)
                    Core.add(acc, f32, acc)
                    Core.add(wsum, w, wsum)
                }
            }

            val wsum3 = s.mat()
            Core.merge(listOf(wsum, wsum, wsum), wsum3)
            Core.divide(acc, wsum3, acc)
            val result = Mat()
            acc.convertTo(result, CvType.CV_8UC3)
            return result
        }
    }

    /**
     * ECC (Euclidean = translation + small rotation) on 1/4-size grayscale frames.
     * Returns a 2x3 matrix already scaled to full resolution, or null if alignment
     * failed / looked implausible (frame is then blended un-aligned and ghost-weighted).
     */
    private fun estimateWarp(ref: Mat, cur: Mat, scale: Double): Mat? {
        val warp = Mat.eye(2, 3, CvType.CV_32F)
        val mask = Mat()
        return try {
            val criteria = TermCriteria(TermCriteria.COUNT + TermCriteria.EPS, 30, 1e-4)
            val cc = Video.findTransformECC(ref, cur, warp, Video.MOTION_EUCLIDEAN, criteria, mask, 5)
            val tx = warp.get(0, 2)[0] * scale
            val ty = warp.get(1, 2)[0] * scale
            val maxShift = ref.cols() * scale * 0.06
            if (cc < 0.5 || abs(tx) > maxShift || abs(ty) > maxShift) {
                warp.release()
                null
            } else {
                warp.put(0, 2, tx)
                warp.put(1, 2, ty)
                warp
            }
        } catch (e: Exception) {
            warp.release()
            null
        } finally {
            mask.release()
        }
    }

    private fun sharpness(gray: Mat): Double {
        val lap = Mat()
        val mean = MatOfDouble()
        val std = MatOfDouble()
        Imgproc.Laplacian(gray, lap, CvType.CV_64F)
        Core.meanStdDev(lap, mean, std)
        val sd = std.get(0, 0)[0]
        lap.release(); mean.release(); std.release()
        return sd * sd
    }

    // ---------------------------------------------------------- tone mapping

    /**
     * CLAHE on the Lab lightness channel, blended with a highlight-protecting weight so
     * shadows/midtones are lifted while bright areas stay untouched (no blown highlights).
     */
    fun enhanceTone(src: Mat, dst: Mat, clipLimit: Double) {
        MatScope().use { s ->
            val lab = s.mat()
            Imgproc.cvtColor(src, lab, Imgproc.COLOR_BGR2Lab)
            val ch = ArrayList<Mat>()
            Core.split(lab, ch)
            ch.forEach { s.keep(it) }
            val l = ch[0]

            val lc = s.mat()
            val clahe = Imgproc.createCLAHE(clipLimit, Size(8.0, 8.0))
            clahe.apply(l, lc)
            clahe.collectGarbage()

            val lf = s.mat()
            l.convertTo(lf, CvType.CV_32F)
            val lcf = s.mat()
            lc.convertTo(lcf, CvType.CV_32F)

            // w = 1 for L <= 55%, linearly falls to 0 at L >= 95%.
            val w = s.mat()
            l.convertTo(w, CvType.CV_32F, -2.5 / 255.0, 2.375)
            Core.max(w, Scalar(0.0), w)
            Core.min(w, Scalar(1.0), w)

            val delta = s.mat()
            Core.subtract(lcf, lf, delta)
            Core.multiply(delta, w, delta)
            Core.add(lf, delta, lf)
            lf.convertTo(l, CvType.CV_8U)

            Core.merge(ch, lab)
            Imgproc.cvtColor(lab, dst, Imgproc.COLOR_Lab2BGR)
        }
    }

    private fun sharpen(m: Mat, amount: Double, sigma: Double) {
        val blur = Mat()
        Imgproc.GaussianBlur(m, blur, Size(0.0, 0.0), sigma)
        Core.addWeighted(m, 1.0 + amount, blur, -amount, 0.0, m)
        blur.release()
    }

    // ------------------------------------------------------------ live preview

    /** Converts an RGBA_8888 analysis frame into an enhanced, upright Bitmap (low-res). */
    fun enhanceLiveFrame(proxy: ImageProxy): Bitmap {
        val plane = proxy.planes[0]
        val w = proxy.width
        val h = proxy.height
        val rowStride = plane.rowStride
        val rowBytes = w * 4
        val buf = plane.buffer
        val bytes = ByteArray(buf.remaining())
        buf.get(bytes)

        return MatScope().use { s ->
            val rgba = s.keep(Mat(h, w, CvType.CV_8UC4))
            if (rowStride == rowBytes) {
                rgba.put(0, 0, bytes)
            } else {
                for (r in 0 until h) rgba.put(r, 0, bytes, r * rowStride, rowBytes)
            }
            val upright = s.keep(rotate(rgba, proxy.imageInfo.rotationDegrees))
            val bgr = s.mat()
            Imgproc.cvtColor(upright, bgr, Imgproc.COLOR_RGBA2BGR)
            val toned = s.mat()
            enhanceTone(bgr, toned, 2.5)
            val outRgba = s.mat()
            Imgproc.cvtColor(toned, outRgba, Imgproc.COLOR_BGR2RGBA)
            val bmp = Bitmap.createBitmap(outRgba.cols(), outRgba.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(outRgba, bmp)
            bmp
        }
    }

    // ------------------------------------------------------------------ utils

    private fun decodeFull(bytes: ByteArray): Mat {
        val raw = Mat(1, bytes.size, CvType.CV_8UC1)
        raw.put(0, 0, bytes)
        val img = Imgcodecs.imdecode(raw, Imgcodecs.IMREAD_COLOR or Imgcodecs.IMREAD_IGNORE_ORIENTATION)
        raw.release()
        if (img.empty()) {
            img.release()
            throw IllegalStateException("JPEG decode failed")
        }
        return img
    }

    private fun decodeSmallGray(bytes: ByteArray): Mat {
        val raw = Mat(1, bytes.size, CvType.CV_8UC1)
        raw.put(0, 0, bytes)
        val img = Imgcodecs.imdecode(
            raw, Imgcodecs.IMREAD_REDUCED_GRAYSCALE_4 or Imgcodecs.IMREAD_IGNORE_ORIENTATION
        )
        raw.release()
        if (img.empty()) {
            img.release()
            throw IllegalStateException("JPEG decode failed")
        }
        return img
    }

    /** Always returns a new Mat (caller owns it). */
    private fun rotate(src: Mat, degrees: Int): Mat {
        val dst = Mat()
        when (((degrees % 360) + 360) % 360) {
            90 -> Core.rotate(src, dst, Core.ROTATE_90_CLOCKWISE)
            180 -> Core.rotate(src, dst, Core.ROTATE_180)
            270 -> Core.rotate(src, dst, Core.ROTATE_90_COUNTERCLOCKWISE)
            else -> src.copyTo(dst)
        }
        return dst
    }
}
