package app.strata.psd.engine
fun main(args: Array<String>) {
    val t0 = System.nanoTime()
    val s = PsdSession(java.io.File(args[0]))
    val t1 = System.nanoTime(); println("parse ${(t1 - t0) / 1e6} ms, workingScale=${s.workingScale}")
    val m = s.composite(s.workingScale); val t2 = System.nanoTime(); println("merged@scale ${m.width}x${m.height} ${(t2 - t1) / 1e6} ms")
    val r = s.renderLayers()!!; val t3 = System.nanoTime(); println("layers decode+composite ${r.width}x${r.height} ${(t3 - t2) / 1e6} ms")
    s.renderLayers()!!; val t4 = System.nanoTime(); println("re-composite (cached) ${(t4 - t3) / 1e6} ms")
}
