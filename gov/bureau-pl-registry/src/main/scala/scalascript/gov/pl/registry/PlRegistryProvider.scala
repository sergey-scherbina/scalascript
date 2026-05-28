package scalascript.gov.pl.registry

import scalascript.gov.*
import scalascript.gov.registry.*
import scalascript.gov.providers.RegistryProvider
import scala.concurrent.{Future, ExecutionContext}

/** Polish business registry provider.
 *  Routes queries to the appropriate underlying system by TaxIdType:
 *  - NIP   → Biała Lista (VAT status) + REGON (full details)
 *  - KRS   → KRS API
 *  - PESEL → CEIDG (sole proprietors)
 *  - REGON → REGON BIR1 */
class PlRegistryProvider(config: PlRegistryConfig) extends RegistryProvider:

  protected val ceidg:      CeidgAdapter      = CeidgAdapter(config)
  protected val regon:      RegonAdapter      = RegonAdapter(config)
  protected val bialaLista: BialaListaAdapter = BialaListaAdapter(config)
  protected val krs:        KrsAdapter        = KrsAdapter(config)

  def lookup(id: TaxIdentifier)(using ExecutionContext): Future[Option[BusinessRecord]] =
    Future {
      id.idType match
        case TaxIdType.NIP =>
          bialaLista.lookup(id.value)
            .orElse(regon.lookupByNip(id.value))
        case TaxIdType.REGON =>
          regon.lookupByRegon(id.value)
        case TaxIdType.KRS =>
          krs.lookupByKrs(id.value)
        case TaxIdType.PESEL =>
          ceidg.lookupByPesel(id.value)
        case other =>
          throw BureauError.UnsupportedOperation(CountryCode.PL, GovDomain.Registry, s"lookup by $other")
    }

  def search(query: String, country: CountryCode)(using ExecutionContext): Future[List[BusinessRecord]] =
    Future {
      val ceidgResults = ceidg.search(query)
      val krsResults   = krs.searchByName(query)
      (ceidgResults ++ krsResults).distinctBy(_.name)
    }

  def checkVatStatus(id: TaxIdentifier)(using ExecutionContext): Future[VatPayerStatus] =
    Future {
      id.idType match
        case TaxIdType.NIP =>
          bialaLista.checkVatStatus(id.value)
        case other =>
          throw BureauError.UnsupportedOperation(CountryCode.PL, GovDomain.Registry, s"VAT status by $other")
    }

  def getDetails(id: TaxIdentifier)(using ExecutionContext): Future[RegistrationDetails] =
    Future {
      id.idType match
        case TaxIdType.KRS =>
          krs.getDetails(id.value)
            .getOrElse(throw BureauError.ApiError(s"KRS: no details for ${id.value}", Some("NOT_FOUND"), Some(404)))
        case TaxIdType.NIP =>
          val record = bialaLista.lookup(id.value)
            .orElse(regon.lookupByNip(id.value))
            .getOrElse(throw BureauError.ApiError(s"NIP ${id.value} not found", Some("NOT_FOUND"), Some(404)))
          RegistrationDetails(record = record, directors = Nil, shareholders = Nil, activities = Nil, capital = None)
        case TaxIdType.REGON =>
          val record = regon.lookupByRegon(id.value)
            .getOrElse(throw BureauError.ApiError(s"REGON ${id.value} not found", Some("NOT_FOUND"), Some(404)))
          RegistrationDetails(record = record, directors = Nil, shareholders = Nil, activities = Nil, capital = None)
        case TaxIdType.PESEL =>
          val record = ceidg.lookupByPesel(id.value)
            .getOrElse(throw BureauError.ApiError(s"PESEL ${id.value} not found in CEIDG", Some("NOT_FOUND"), Some(404)))
          RegistrationDetails(record = record, directors = Nil, shareholders = Nil, activities = Nil, capital = None)
        case other =>
          throw BureauError.UnsupportedOperation(CountryCode.PL, GovDomain.Registry, s"getDetails by $other")
    }
