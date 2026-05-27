package scalascript.payments.compliance.mock

/** ServiceLoader entry point for the mock compliance adapter (testing only). */
class MockCompliancePlugin:
  def id:          String                  = "mock"
  def displayName: String                  = "Mock Compliance Provider"
  def create():    MockComplianceProvider  = MockComplianceProvider.allApproved
