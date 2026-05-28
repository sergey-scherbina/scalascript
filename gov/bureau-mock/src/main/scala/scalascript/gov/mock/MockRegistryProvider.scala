package scalascript.gov.mock

import scalascript.gov.*
import scalascript.gov.providers.RegistryProvider
import scalascript.gov.registry.*
import java.time.Instant
import scala.collection.mutable
import scala.concurrent.{Future, ExecutionContext}

/** Configurable in-memory RegistryProvider for testing. */
class MockRegistryProvider(
  var succeed:       Boolean                    = true,
  var defaultRecord: Option[BusinessRecord]     = None,
  var defaultStatus: Option[VatPayerStatus]     = None,
) extends RegistryProvider:

  private val _lookups   = mutable.ListBuffer[TaxIdentifier]()
  private val _searches  = mutable.ListBuffer[(String, CountryCode)]()
  private val _vatChecks = mutable.ListBuffer[TaxIdentifier]()

  def recordedLookups:   List[TaxIdentifier]           = _lookups.toList
  def recordedSearches:  List[(String, CountryCode)]   = _searches.toList
  def recordedVatChecks: List[TaxIdentifier]           = _vatChecks.toList

  def reset(): Unit =
    _lookups.clear()
    _searches.clear()
    _vatChecks.clear()

  def lookup(id: TaxIdentifier)(using ExecutionContext): Future[Option[BusinessRecord]] =
    _lookups += id
    if succeed then Future.successful(defaultRecord)
    else Future.failed(BureauError.ApiError("mock lookup failure"))

  def search(query: String, country: CountryCode)(using ExecutionContext): Future[List[BusinessRecord]] =
    _searches += ((query, country))
    if succeed then Future.successful(defaultRecord.toList)
    else Future.failed(BureauError.ApiError("mock search failure"))

  def checkVatStatus(id: TaxIdentifier)(using ExecutionContext): Future[VatPayerStatus] =
    _vatChecks += id
    if succeed then
      Future.successful(defaultStatus.getOrElse(VatPayerStatus(
        active = true, id = id, name = None, bankAccounts = Nil, checkedAt = Instant.now()
      )))
    else Future.failed(BureauError.ApiError("mock VAT check failure"))

  def getDetails(id: TaxIdentifier)(using ExecutionContext): Future[RegistrationDetails] =
    _lookups += id
    defaultRecord match
      case Some(r) if succeed =>
        Future.successful(RegistrationDetails(r, Nil, Nil, Nil, None))
      case _ if !succeed =>
        Future.failed(BureauError.ApiError("mock details failure"))
      case _ =>
        Future.failed(BureauError.ApiError(s"${id.value} not found", Some("NOT_FOUND"), Some(404)))
