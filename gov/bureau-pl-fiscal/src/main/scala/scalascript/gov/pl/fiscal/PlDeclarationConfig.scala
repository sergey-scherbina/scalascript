package scalascript.gov.pl.fiscal

case class PlDeclarationConfig(
  baseUrl: String = "https://e-deklaracje.mf.gov.pl",
)

object PlDeclarationConfig:
  def fromEnv: PlDeclarationConfig = PlDeclarationConfig(
    baseUrl = sys.env.getOrElse("EDEK_BASE_URL", "https://e-deklaracje.mf.gov.pl"),
  )

  def test: PlDeclarationConfig = PlDeclarationConfig(
    baseUrl = "https://test-e-deklaracje.mf.gov.pl",
  )
