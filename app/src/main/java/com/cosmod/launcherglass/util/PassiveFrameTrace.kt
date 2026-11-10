package com.cosmod.launcherglass.util

import java.io.File
import java.util.concurrent.TimeUnit

/** Stops and collects an explicitly armed debug session; never starts tracing on its own. */
internal object PassiveFrameTrace {
    // OEM traced may support neither CloneSession nor detach/attach. SIGINT
    // flushes the recorder normally. Validate both PID and exact config first.
    private val stopCommand="""
        trace_pid=${'$'}(cat /data/local/tmp/cherrypick-frames.pid 2>/dev/null)
        case "${'$'}trace_pid" in ''|*[!0-9]*) exit 1;; esac
        tr '\000' ' ' < /proc/${'$'}trace_pid/cmdline | grep -Fq -- 'perfetto --background-wait --txt -c /data/local/tmp/cherrypick-frames.pbtxt' || exit 1
        kill -INT "${'$'}trace_pid" || exit 1
        for poll in ${'$'}(seq 1 30); do
            if ! kill -0 "${'$'}trace_pid" 2>/dev/null; then
                cat /data/local/tmp/cherrypick-frames-final.pftrace
                exit ${'$'}?
            fi
            sleep .1
        done
        exit 1
    """.trimIndent()
    fun snapshot(directory:File):File? {
        val destination=File(directory,"frame-snapshot.pftrace")
        val errors=File(directory,"frame-snapshot-errors.txt")
        var process:Process?=null
        return try {
            destination.delete()
            process=ProcessBuilder("su","-c",stopCommand).redirectOutput(destination).redirectError(errors).start()
            val finished=process.waitFor(10,TimeUnit.SECONDS)
            if(!finished)process.destroyForcibly()
            if(finished && process.exitValue()==0 && destination.length() in 1024L..24L*1024*1024)destination
            else {destination.delete();null}
        }catch(_:Exception){destination.delete();null}
        finally{process?.let {if(it.isAlive)it.destroyForcibly()};errors.delete()}
    }
}
