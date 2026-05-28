package scalascript.gov

enum GovDomain:
  case Fiscal       // taxes, VAT, e-invoicing, audit files
  case Social       // social insurance, payroll declarations
  case Registry     // business registries, VAT payer lookups
  case Customs      // import/export, INTRASTAT
  case Statistics   // statistical reporting
  case Environment  // environmental taxes, waste register (BDO)
  case Identity     // eID authentication, digital certificates
