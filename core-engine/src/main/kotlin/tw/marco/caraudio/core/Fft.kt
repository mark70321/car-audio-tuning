package tw.marco.caraudio.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** 原地（in-place）radix-2 FFT。長度必須是 2 的次方；inverse = true 時結果已除以 n。 */
object Fft {
    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean = false) {
        val n = re.size
        require(n == im.size && n > 0 && n and (n - 1) == 0) { "FFT length must be a power of 2, got $n" }

        // 位元反轉排序
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }

        var len = 2
        while (len <= n) {
            val angle = 2 * PI / len * (if (inverse) 1 else -1)
            val wRe = cos(angle)
            val wIm = sin(angle)
            val half = len / 2
            var start = 0
            while (start < n) {
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until half) {
                    val a = start + k
                    val b = a + half
                    val tRe = re[b] * curRe - im[b] * curIm
                    val tIm = re[b] * curIm + im[b] * curRe
                    re[b] = re[a] - tRe
                    im[b] = im[a] - tIm
                    re[a] += tRe
                    im[a] += tIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                start += len
            }
            len = len shl 1
        }

        if (inverse) {
            for (i in 0 until n) {
                re[i] /= n
                im[i] /= n
            }
        }
    }

    fun nextPowerOfTwo(x: Int): Int {
        var n = 1
        while (n < x) n = n shl 1
        return n
    }
}
