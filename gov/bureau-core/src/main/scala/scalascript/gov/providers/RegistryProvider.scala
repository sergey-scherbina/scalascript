package scalascript.gov.providers

import scalascript.gov.*
import scalascript.gov.registry.*
import scala.concurrent.{Future, ExecutionContext}

trait RegistryProvider:
  def lookup(id: TaxIdentifier)(using ExecutionContext): Future[Option[BusinessRecord]]
  def search(query: String, country: CountryCode)(using ExecutionContext): Future[List[BusinessRecord]]
  def checkVatStatus(id: TaxIdentifier)(using ExecutionContext): Future[VatPayerStatus]
  def getDetails(id: TaxIdentifier)(using ExecutionContext): Future[RegistrationDetails]
