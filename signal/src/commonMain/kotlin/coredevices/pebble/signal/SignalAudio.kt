package coredevices.pebble.signal

object SignalAudio {
        fun wave(pcm: ByteArray, rate: Int): ByteArray {
            require(rate > 0 && rate <= 192000 && pcm.size % 2 == 0)
            val result = ByteArray(44 + pcm.size)
            fun word(at: Int, value: Int, bytes: Int) { repeat(bytes) { result[at + it] = (value ushr (it * 8)).toByte() } }
            "RIFF".encodeToByteArray().copyInto(result); word(4, pcm.size + 36, 4)
            "WAVEfmt ".encodeToByteArray().copyInto(result, 8)
            word(16, 16, 4); word(20, 1, 2); word(22, 1, 2); word(24, rate, 4)
            word(28, rate * 2, 4); word(32, 2, 2); word(34, 16, 2)
            "data".encodeToByteArray().copyInto(result, 36); word(40, pcm.size, 4)
            pcm.copyInto(result, 44)
            return result
        }
}
