package io.moonlightdevelopment.runhytale

import java.io.OutputStream

class TeeOutputStream(
    private val a: OutputStream, private val b: OutputStream
) : OutputStream() {
    override fun write(bte: Int) {
        a.write(bte)
        b.write(bte)
    }

    override fun write(buf: ByteArray, off: Int, len: Int) {
        a.write(buf, off, len)
        b.write(buf, off, len)
    }

    override fun flush() {
        a.flush()
        b.flush()
    }
}
