package scalascript.db

case class PgConfig(
  host:      String = "localhost",
  port:      Int    = 5432,
  database:  String,
  user:      String,
  password:  String,
  poolSize:  Int    = 10,
  fetchSize: Int    = 1000,
):
  def jdbcUrl: String = s"jdbc:postgresql://$host:$port/$database"
