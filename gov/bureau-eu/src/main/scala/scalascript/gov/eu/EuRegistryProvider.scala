package scalascript.gov.eu

import scalascript.gov.*
import scalascript.gov.providers.RegistryProvider
import scalascript.gov.registry.*
import scala.concurrent.{Future, ExecutionContext}

/** EU-level RegistryProvider backed by VIES VAT verification.
 *
 *  Supports VatEU lookups only; other tax-id types fail with UnsupportedOperation. */
class EuRegistryProvider(viesAdapter: EuViesAdapter = EuViesAdapter()) extends RegistryProvider:

  def lookup(id: TaxIdentifier)(using ExecutionContext): Future[Option[BusinessRecord]] =
    id.idType match
      case TaxIdType.VatEU =>
        val (cc, vat) = splitVatEu(id.value)
        viesAdapter.checkVat(cc, vat).map { status =>
          if status.active then
            Some(BusinessRecord(
              name         = status.name.getOrElse(""),
              legalForm    = None,
              taxIds       = List(status.id),
              address      = None,
              status       = RegistrationStatus.Active,
              registeredAt = None,
              metadata     = Map("eu_vat" -> id.value),
            ))
          else
            None
        }
      case _ =>
        Future.failed(BureauError.UnsupportedOperation(CountryCode.EU, GovDomain.Registry,
          s"lookup(${id.idType}) not supported by EU provider"))

  def search(query: String, country: CountryCode)(using ExecutionContext): Future[List[BusinessRecord]] =
    Future.failed(BureauError.UnsupportedOperation(CountryCode.EU, GovDomain.Registry,
      "search not supported by EU VIES"))

  def checkVatStatus(id: TaxIdentifier)(using ExecutionContext): Future[VatPayerStatus] =
    id.idType match
      case TaxIdType.VatEU =>
        val (cc, vat) = splitVatEu(id.value)
        viesAdapter.checkVat(cc, vat)
      case _ =>
        Future.failed(BureauError.UnsupportedOperation(CountryCode.EU, GovDomain.Registry,
          s"checkVatStatus(${id.idType}) not supported by EU provider"))

  def getDetails(id: TaxIdentifier)(using ExecutionContext): Future[RegistrationDetails] =
    lookup(id).flatMap {
      case Some(record) => Future.successful(RegistrationDetails(record, Nil, Nil, Nil, None))
      case None         => Future.failed(BureauError.ApiError(s"VAT ${id.value} not found or not active"))
    }

  private def splitVatEu(vatFull: String): (String, String) =
    if vatFull.length < 3 then ("", vatFull) else (vatFull.take(2).toUpperCase, vatFull.drop(2))
