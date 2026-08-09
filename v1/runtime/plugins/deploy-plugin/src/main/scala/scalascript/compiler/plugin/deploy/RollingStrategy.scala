package scalascript.compiler.plugin.deploy

enum RollingStrategy:
  case Rolling(maxUnavailable: Int = 1, waitDrainMs: Int = 30_000)
  case BlueGreen(observeMs: Int = 60_000)
  case Canary(percent: Int, observeMs: Int = 120_000)
