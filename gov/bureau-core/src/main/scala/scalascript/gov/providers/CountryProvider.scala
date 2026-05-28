package scalascript.gov.providers

import scalascript.gov.*

trait CountryProvider:
  def country:      CountryCode
  def displayName:  String
  def legalForms:   Set[LegalForm]
  def capabilities: Set[GovDomain]

  def fiscal:      Option[FiscalProvider]     = None
  def social:      Option[SocialProvider]     = None
  def registry:    Option[RegistryProvider]   = None
  def customs:     Option[CustomsProvider]    = None
  def statistics:  Option[StatisticsProvider] = None
  def environment: Option[EnvProvider]        = None
