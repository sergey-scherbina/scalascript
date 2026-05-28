package scalascript.gov

enum LegalForm:
  case SoleProprietor                   // JDG (PL), Einzelunternehmer (DE), auto-entrepreneur (FR)
  case LimitedLiabilityCompany          // sp. z o.o. (PL), GmbH (DE), SARL (FR), Ltd (UK)
  case JointStockCompany                // SA (PL), AG (DE), PLC (UK), SAS (FR)
  case GeneralPartnership               // spółka jawna (PL), OHG (DE), SNC (FR)
  case LimitedPartnership               // spółka komandytowa (PL), KG (DE), SCS (FR)
  case LimitedJointStockPartnership     // spółka komandytowo-akcyjna (PL)
  case ProfessionalPartnership          // spółka partnerska (PL)
  case Cooperative                      // spółdzielnia (PL), Genossenschaft (DE)
  case Foundation                       // fundacja (PL), Stiftung (DE)
  case Association                      // stowarzyszenie (PL), Verein (DE)
  case Branch                           // oddział (PL) — branch of foreign entity
  case CivilPartnership                 // spółka cywilna (PL) — not a legal person
  case Other(name: String)
