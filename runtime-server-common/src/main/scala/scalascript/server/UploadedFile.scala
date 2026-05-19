package scalascript.server

/** POJO description of a single `multipart/form-data` upload part.
 *
 *  `bytes` is the ISO-8859-1 view of the raw bytes (1 char = 1 byte) when
 *  the part is small enough to keep in memory; round-trip back to a byte
 *  array with `bytes.getBytes("ISO-8859-1")`.  When a part exceeds the
 *  spool-to-disk threshold, `bytes` is empty and `path` points to the
 *  temp file holding the bytes. */
case class UploadedFile(
    name:        String,
    filename:    String,
    contentType: String,
    size:        Int,
    bytes:       String,
    path:        String = ""
)
