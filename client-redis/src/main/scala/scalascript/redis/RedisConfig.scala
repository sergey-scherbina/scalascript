package scalascript.redis

case class RedisConfig(
  host:     String         = "localhost",
  port:     Int            = 6379,
  password: Option[String] = None,
  database: Int            = 0,
  tls:      Boolean        = false,
):
  def uri: String =
    val auth  = password.map(p => s":$p@").getOrElse("")
    val proto = if tls then "rediss" else "redis"
    s"$proto://${auth}$host:$port/$database"
