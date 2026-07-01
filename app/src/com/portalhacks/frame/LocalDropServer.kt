package com.portalhacks.frame

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A tiny embedded HTTP server that turns the frame into an "AirDrop for Portal" target:
 * any phone on the same Wi-Fi opens the frame's address in its browser, picks photos from
 * its camera roll (the browser's native file picker), and they appear on the frame — no
 * app, no account, no cloud.
 *
 * `GET /` serves the upload page; `POST /upload` saves the posted image(s) into
 * [LocalUploads] and calls [onUpload] so the slideshow can show them immediately. The
 * server is plaintext and LAN-only **by design** (the photos already traverse the home
 * network; there are no credentials). Uploads are size-capped and must decode as a known
 * image type, and the client's filename is never used as a path — we mint our own.
 *
 * Built on raw [ServerSocket] + hand-rolled HTTP, matching this app's no-HTTP-library
 * convention (it hand-rolls its outbound `HttpURLConnection` calls too).
 */
class LocalDropServer(
    context: Context,
    private val token: String,
    private val onUpload: () -> Unit,
) {
    private val ctx = context.applicationContext
    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private val openConns = java.util.concurrent.atomic.AtomicInteger(0)
    private val activeSockets = java.util.Collections.synchronizedSet(HashSet<Socket>())
    // Bound how many uploads buffer in memory at once (each holds the whole POST body),
    // so even many authenticated peers can't OOM the process.
    private val uploadGate = java.util.concurrent.Semaphore(MAX_CONCURRENT_UPLOADS)

    /** Start accepting connections (idempotent). Binds on a background daemon thread. */
    fun start() {
        if (running.getAndSet(true)) return
        Thread({ serve() }, "LocalDropServer").apply { isDaemon = true }.start()
    }

    /** Stop the server and release the port (idempotent). */
    fun stop() {
        if (!running.getAndSet(false)) return
        try {
            server?.close()
        } catch (_: IOException) {
        }
        server = null
        DropServerStatus.port = -1
        // Close any in-flight client connections so their handlers don't keep running
        // (and call back) after the owning service is torn down.
        synchronized(activeSockets) {
            for (sk in activeSockets) {
                try {
                    sk.close()
                } catch (_: IOException) {
                }
            }
            activeSockets.clear()
        }
    }

    private fun serve() {
        val s = bind()
        if (s == null) {
            running.set(false)
            return
        }
        server = s
        DropServerStatus.port = s.localPort
        Log.i(TAG, "drop server listening — ${DropServerStatus.url(ctx) ?: "http://<device-ip>:${s.localPort}/"}")
        while (running.get()) {
            val sock = try {
                s.accept()
            } catch (e: IOException) {
                break // closed by stop()
            }
            // Cap concurrent connections so a flood can't exhaust threads/memory.
            if (openConns.get() >= MAX_CONNECTIONS) {
                try {
                    sock.close()
                } catch (_: IOException) {
                }
                continue
            }
            openConns.incrementAndGet()
            activeSockets.add(sock)
            Thread({
                try {
                    handle(sock)
                } finally {
                    activeSockets.remove(sock)
                    openConns.decrementAndGet()
                }
            }, "LocalDrop-conn").apply { isDaemon = true }.start()
        }
        DropServerStatus.port = -1
        try {
            s.close()
        } catch (_: IOException) {
        }
    }

    private fun bind(): ServerSocket? {
        for (p in PORTS) {
            val ss = ServerSocket()
            try {
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(p)) // all interfaces, so the LAN can reach it
                return ss
            } catch (e: IOException) {
                try {
                    ss.close() // don't leak the unbound socket before trying the next port
                } catch (_: IOException) {
                }
            }
        }
        Log.e(TAG, "drop server: no port available in $PORTS")
        return null
    }

    private fun handle(sock: Socket) {
        try {
            sock.soTimeout = 15000
            val input = BufferedInputStream(sock.getInputStream())
            val out = sock.getOutputStream()
            val head = readHead(input) ?: return
            val lines = String(head, StandardCharsets.ISO_8859_1).split("\r\n")
            val request = lines.firstOrNull()?.split(" ") ?: return
            if (request.size < 2) {
                return
            }
            val method = request[0].uppercase()
            val target = request[1]
            val path = target.substringBefore('?')
            // Only a phone that scanned the on-screen QR has the token; everyone else is refused.
            val authed = DropAuth.matches(token, queryParam(target, "k"))
            val headers = parseHeaders(lines)

            when {
                method == "GET" && (path == "/" || path == "/index.html") ->
                    if (authed) respond(out, 200, HTML, page(null))
                    else respond(out, 403, HTML, FORBIDDEN.toByteArray())
                method == "POST" && path == "/upload" ->
                    if (authed) handleUpload(input, headers, out)
                    else respond(out, 403, HTML, FORBIDDEN.toByteArray())
                else ->
                    respond(out, 404, "text/plain; charset=utf-8", "not found".toByteArray())
            }
            out.flush()
        } catch (e: Exception) {
            Log.w(TAG, "connection error", e)
        } finally {
            try {
                sock.close()
            } catch (_: IOException) {
            }
        }
    }

    /** Extract a query parameter value from a request target like `/upload?k=abc`. */
    private fun queryParam(target: String, name: String): String? {
        val q = target.substringAfter('?', "")
        if (q.isEmpty()) return null
        for (kv in q.split("&")) {
            val eq = kv.indexOf('=')
            if (eq > 0 && kv.substring(0, eq) == name) {
                return try {
                    java.net.URLDecoder.decode(kv.substring(eq + 1), "UTF-8")
                } catch (e: Exception) {
                    kv.substring(eq + 1)
                }
            }
        }
        return null
    }

    private fun handleUpload(input: BufferedInputStream, headers: Map<String, String>, out: OutputStream) {
        val contentType = headers["content-type"] ?: ""
        val boundary = contentType.substringAfter("boundary=", "").trim().trim('"')
        val length = headers["content-length"]?.toIntOrNull() ?: -1
        if (boundary.isEmpty() || length <= 0) {
            respondUpload(out, 400, 0, 0, "error",
                "We couldn't read those photos. Please go back and choose them again.")
            return
        }
        if (length > MAX_BODY_BYTES) {
            respondUpload(out, 400, 0, 0, "toobig",
                "Those photos are too large to send together. Try a few at a time.")
            return
        }
        // Only a bounded number of uploads buffer in memory concurrently; the rest get a
        // "busy" so a burst of big POSTs can't OOM the process.
        if (!uploadGate.tryAcquire()) {
            respondUpload(out, 503, 0, 0, "busy",
                "The frame is receiving other photos right now. Nothing was added — " +
                    "wait a moment, then choose them again.")
            return
        }
        try {
            val body = readBody(input, length) ?: run {
                respondUpload(out, 400, 0, 0, "error",
                    "The upload didn't finish. Check that your phone is still on the same Wi-Fi, then try again.")
                return
            }
            var saved = 0
            var unsupported = 0
            var failed = 0
            var fileParts = 0
            for (part in MultipartParser.parts(body, boundary)) {
                if (part.filename.isNullOrEmpty() || part.data.isEmpty()) continue
                fileParts++
                val ext = imageExt(part.data, part.filename, part.contentType)
                when {
                    ext == null -> unsupported++
                    LocalUploads.save(ctx, part.data, ext) != null -> saved++
                    else -> failed++
                }
            }
            if (saved > 0) {
                try {
                    onUpload()
                } catch (e: Exception) {
                    Log.w(TAG, "onUpload callback failed", e)
                }
            }
            val rejected = unsupported + failed
            val status = if (saved > 0) 200 else 400
            val reason = when {
                saved > 0 && rejected == 0 -> "ok"
                saved > 0 -> "partial"
                unsupported > 0 -> "unsupported"
                fileParts == 0 -> "none"
                else -> "error"
            }
            respondUpload(out, status, saved, rejected, reason, uploadMessage(saved, unsupported, failed))
        } finally {
            uploadGate.release()
        }
    }

    /** Accurate, family-friendly copy for the upload outcome (used on the result page). */
    private fun uploadMessage(saved: Int, unsupported: Int, failed: Int): String {
        val total = saved + unsupported + failed
        return when {
            saved > 0 && unsupported == 0 && failed == 0 ->
                if (saved == 1) "Added 1 photo — it should be on the frame now."
                else "Added $saved photos — they should be on the frame now."
            saved > 0 ->
                "Added $saved of $total. ${rejectNote(unsupported, failed)}"
            unsupported > 0 ->
                "We couldn't add those. Choose JPEG, PNG, HEIC, WebP, or GIF photos."
            else ->
                "No photos were added. Make sure your phone is on the same Wi-Fi, then try again."
        }
    }

    private fun rejectNote(unsupported: Int, failed: Int): String = when {
        unsupported > 0 && failed > 0 -> "The rest weren't supported or couldn't be saved."
        unsupported > 0 ->
            if (unsupported == 1) "One file wasn't a supported photo." else "$unsupported files weren't supported photos."
        else -> if (failed == 1) "One couldn't be saved." else "$failed couldn't be saved."
    }

    /** Render the result page with the message, plus machine-readable headers for the JS path. */
    private fun respondUpload(out: OutputStream, status: Int, added: Int, rejected: Int, reason: String, message: String) {
        val extra = "X-Frame-Added: $added\r\nX-Frame-Rejected: $rejected\r\nX-Frame-Reason: $reason\r\n"
        respond(out, status, HTML, page(message), extra)
    }

    /** Read request bytes up to and including the blank-line header terminator (CRLF CRLF). */
    private fun readHead(input: BufferedInputStream): ByteArray? {
        val buf = ByteArrayOutputStream()
        var state = 0 // progress through CR LF CR LF
        while (true) {
            val b = input.read()
            if (b == -1) return if (buf.size() == 0) null else buf.toByteArray()
            buf.write(b)
            state = when (state) {
                0 -> if (b == CR) 1 else 0
                1 -> if (b == LF) 2 else if (b == CR) 1 else 0
                2 -> if (b == CR) 3 else 0
                3 -> if (b == LF) 4 else if (b == CR) 1 else 0
                else -> 0
            }
            if (state == 4) return buf.toByteArray()
            if (buf.size() > MAX_HEAD_BYTES) return null
        }
    }

    /** Read exactly [length] body bytes that follow the header. */
    private fun readBody(input: BufferedInputStream, length: Int): ByteArray? {
        val body = ByteArray(length)
        var off = 0
        while (off < length) {
            val r = input.read(body, off, length - off)
            if (r < 0) return null
            off += r
        }
        return body
    }

    private fun parseHeaders(lines: List<String>): Map<String, String> {
        val map = HashMap<String, String>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isEmpty()) break
            val c = line.indexOf(':')
            if (c > 0) {
                map[line.substring(0, c).trim().lowercase()] = line.substring(c + 1).trim()
            }
        }
        return map
    }

    private fun respond(out: OutputStream, status: Int, contentType: String, body: ByteArray, extra: String = "") {
        val reason = when (status) {
            200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"
            404 -> "Not Found"; 503 -> "Service Unavailable"; else -> "OK"
        }
        val header = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${body.size}\r\n" +
            extra +
            "Connection: close\r\n" +
            "Cache-Control: no-store\r\n\r\n"
        out.write(header.toByteArray(StandardCharsets.ISO_8859_1))
        out.write(body)
    }

    /** Detect a supported image type from magic bytes; return its file extension or null. */
    private fun imageExt(data: ByteArray, filename: String, contentType: String): String? {
        if (data.size < 12) return null
        fun u(i: Int) = data[i].toInt() and 0xff
        // JPEG FF D8 FF
        if (u(0) == 0xFF && u(1) == 0xD8 && u(2) == 0xFF) return "jpg"
        // PNG 89 50 4E 47
        if (u(0) == 0x89 && u(1) == 0x50 && u(2) == 0x4E && u(3) == 0x47) return "png"
        // GIF 47 49 46 38
        if (u(0) == 0x47 && u(1) == 0x49 && u(2) == 0x46 && u(3) == 0x38) return "gif"
        // WEBP "RIFF"...."WEBP"
        if (u(0) == 0x52 && u(1) == 0x49 && u(2) == 0x46 && u(3) == 0x46 &&
            u(8) == 0x57 && u(9) == 0x45 && u(10) == 0x42 && u(11) == 0x50
        ) {
            return "webp"
        }
        // HEIC/HEIF: "....ftyp" with a heic/heif/mif1 brand
        if (u(4) == 0x66 && u(5) == 0x74 && u(6) == 0x79 && u(7) == 0x70) {
            val brand = String(data, 8, 4, StandardCharsets.ISO_8859_1).lowercase()
            if (brand.startsWith("hei") || brand.startsWith("mif") || brand.startsWith("msf")) return "heic"
        }
        return null
    }

    /** The upload page with the token injected into the form action and an optional message. */
    private fun page(message: String?): ByteArray {
        return PAGE
            .replace(ACTION_SLOT, "/upload?k=$token")
            .replace(SLOT, message ?: "")
            .toByteArray()
    }

    companion object {
        private const val TAG = "PortalFrame"
        private const val CR = '\r'.code
        private const val LF = '\n'.code

        // Preferred ports, in order; first free one wins.
        private val PORTS = listOf(8080, 8088, 8888, 8181, 0) // 0 = any free port as last resort

        private const val MAX_HEAD_BYTES = 64 * 1024
        // Cap a whole multipart POST (a few photos) so a hostile client can't exhaust memory/disk.
        private const val MAX_BODY_BYTES = 32 * 1024 * 1024
        // How many uploads may buffer in memory at once (worst case ~MAX_BODY_BYTES each + a copy).
        private const val MAX_CONCURRENT_UPLOADS = 2

        private const val SLOT = "<!--SLOT-->"
        private const val ACTION_SLOT = "__ACTION__"
        private const val HTML = "text/html; charset=utf-8"

        // Cap concurrent connections so a flood can't exhaust threads/memory.
        private const val MAX_CONNECTIONS = 16

        /** Shown when a request arrives without the valid token (e.g. a stale/guessed link). */
        private val FORBIDDEN = """
            <!doctype html><html lang="en"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Frame</title></head>
            <body style="font-family:-apple-system,BlinkMacSystemFont,sans-serif;background:#0d0f12;
              color:#f3f4f6;display:flex;min-height:100vh;align-items:center;justify-content:center;
              text-align:center;padding:32px;margin:0;">
            <p style="max-width:32ch;line-height:1.5;">This link isn&rsquo;t valid anymore.<br>
            On the frame, open <b>Frame Settings</b>, find <b>Add photos from a phone</b>,
            and scan the QR code again.</p>
            </body></html>
        """.trimIndent()

        /** The mobile upload page served to the phone's browser. */
        private val PAGE = """
            <!doctype html><html lang="en"><head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
            <title>Add photos to Frame</title>
            <style>
              :root { color-scheme: dark; }
              * { box-sizing: border-box; }
              body { margin:0; min-height:100vh; display:flex; flex-direction:column;
                align-items:center; justify-content:center; gap:20px; padding:28px;
                font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;
                background:#0d0f12; color:#f3f4f6; }
              h1 { font-size:26px; margin:0; font-weight:650; text-align:center; }
              .sub { margin:0; color:#9aa2ad; font-size:16px; text-align:center; max-width:32ch; }
              .msg { color:#7fd1a0; font-size:18px; font-weight:600; text-align:center; max-width:34ch; }
              .msg:empty { display:none; }
              .msg.err { color:#f0a3a3; }
              form { width:100%; max-width:420px; display:flex; flex-direction:column; gap:16px; }
              input[type=file] { display:none; }
              .btn { display:flex; align-items:center; justify-content:center;
                min-height:64px; border-radius:18px; font-size:19px; font-weight:600;
                border:none; text-decoration:none; cursor:pointer; }
              .pick { background:#1b1f25; color:#f3f4f6; border:1.5px solid #2c323b; }
              .send { background:#4c8bf5; color:#fff; }
              .send[disabled] { opacity:.4; }
              .count { font-size:15px; color:#9aa2ad; text-align:center; min-height:20px; }
              .status { display:flex; align-items:center; gap:10px; color:#cdd3da; font-size:16px;
                text-align:center; }
              .status[hidden] { display:none; }
              .spin { width:18px; height:18px; border-radius:50%; border:3px solid #2c323b;
                border-top-color:#4c8bf5; animation:spin 0.8s linear infinite; flex:none; }
              @keyframes spin { to { transform:rotate(360deg); } }
            </style></head><body>
              <h1>Add photos to Frame</h1>
              <p class="sub">Pick photos from this phone &mdash; they&rsquo;ll appear on the frame
                right away. No app or account needed.</p>
              <div class="msg" id="msg" role="status" aria-live="polite"><!--SLOT--></div>
              <form id="f" method="post" action="__ACTION__" enctype="multipart/form-data">
                <label class="btn pick" for="file">Choose photos</label>
                <input id="file" name="file" type="file" accept="image/*" multiple>
                <div class="count" id="count" role="status" aria-live="polite"></div>
                <button class="btn send" id="send" type="submit" disabled>Add to Frame</button>
              </form>
              <div class="status" id="status" role="status" aria-live="polite" hidden>
                <span class="spin" aria-hidden="true"></span><span id="statusText">Sending&hellip;</span>
              </div>
              <script>
                function byId(i){return document.getElementById(i);}
                var file=byId('file'),send=byId('send'),count=byId('count'),f=byId('f'),
                    msg=byId('msg'),statusEl=byId('status'),statusText=byId('statusText');
                file.addEventListener('change',function(){
                  var n=file.files.length; send.disabled=n===0; send.textContent='Add to Frame';
                  count.textContent=n?(n+(n===1?' photo selected':' photos selected')):'';
                });
                function extractMsg(html){
                  var m=html.match(/id="msg"[^>]*>([\s\S]*?)<\/div>/);
                  return m?m[1].trim():'';
                }
                if(window.fetch&&window.FormData){
                  f.addEventListener('submit',function(ev){
                    if(file.files.length===0)return;
                    ev.preventDefault();
                    var n=file.files.length;
                    msg.textContent=''; msg.className='msg';
                    send.disabled=true;
                    statusEl.hidden=false;
                    statusText.textContent='Sending '+n+(n===1?' photo… keep this page open.':' photos… keep this page open.');
                    fetch(f.action,{method:'POST',body:new FormData(f)}).then(function(r){
                      return r.text().then(function(t){return {ok:r.ok,added:parseInt(r.headers.get('X-Frame-Added')||'0',10),html:t};});
                    }).then(function(res){
                      statusEl.hidden=true;
                      var text=extractMsg(res.html);
                      msg.textContent=text||(res.ok?'Added to the frame.':'Something went wrong — please try again.');
                      msg.className=res.added>0?'msg':'msg err';
                      if(res.added>0){f.reset();count.textContent='';send.textContent='Add more photos';send.disabled=true;}
                      else{send.disabled=false;}
                    }).catch(function(){
                      statusEl.hidden=true;
                      msg.textContent='Couldn\'t reach the frame. Make sure your phone is on the same Wi-Fi, then try again.';
                      msg.className='msg err';
                      send.disabled=false;
                    });
                  });
                }
              </script>
            </body></html>
        """.trimIndent()
    }
}

/** Minimal `multipart/form-data` parser operating on raw bytes (binary-safe). */
private object MultipartParser {

    class Part(val filename: String?, val contentType: String, val data: ByteArray)

    fun parts(body: ByteArray, boundary: String): List<Part> {
        val dash = ("--$boundary").toByteArray(StandardCharsets.ISO_8859_1)
        // Per RFC 2046 every delimiter after the first is preceded by CRLF; anchoring on it
        // means a boundary-like byte run inside image data can't falsely truncate a part.
        val crlfDash = ("\r\n--$boundary").toByteArray(StandardCharsets.ISO_8859_1)
        val out = ArrayList<Part>()
        // The first delimiter sits at the start of the body (no leading CRLF required).
        var i = indexOf(body, dash, 0)
        while (i >= 0) {
            var start = i + dash.size
            // "--" right after a boundary marks the end of the multipart body.
            if (start + 1 < body.size &&
                body[start] == '-'.code.toByte() && body[start + 1] == '-'.code.toByte()
            ) {
                break
            }
            // Skip the CRLF that ends the boundary line.
            if (start + 1 < body.size &&
                body[start] == '\r'.code.toByte() && body[start + 1] == '\n'.code.toByte()
            ) {
                start += 2
            }
            // The part's bytes run up to the CRLF that precedes the next boundary.
            val next = indexOf(body, crlfDash, start)
            if (next < 0) break
            parsePart(body, start, next)?.let { out.add(it) }
            i = next + 2 // resume at the "--boundary" following the matched CRLF
        }
        return out
    }

    private fun parsePart(body: ByteArray, start: Int, end: Int): Part? {
        val sep = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        val h = indexOf(body, sep, start)
        if (h < 0 || h >= end) return null
        val headerText = String(body, start, h - start, StandardCharsets.ISO_8859_1)
        var filename: String? = null
        var contentType = ""
        for (line in headerText.split("\r\n")) {
            val low = line.lowercase()
            if (low.startsWith("content-disposition")) {
                val m = Regex("filename=\"([^\"]*)\"").find(line)
                if (m != null) filename = m.groupValues[1]
            } else if (low.startsWith("content-type")) {
                contentType = line.substringAfter(':').trim()
            }
        }
        val dataStart = h + sep.size
        if (dataStart > end) return null
        val data = body.copyOfRange(dataStart, end)
        return Part(filename, contentType, data)
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        if (needle.isEmpty()) return -1
        var i = from.coerceAtLeast(0)
        val last = haystack.size - needle.size
        while (i <= last) {
            var k = 0
            while (k < needle.size && haystack[i + k] == needle[k]) k++
            if (k == needle.size) return i
            i++
        }
        return -1
    }
}
