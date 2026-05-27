package scalascript.compiler.plugin.deploy

/** Generates Kubernetes YAML manifests (Deployment, Service, Ingress, ConfigMap).
 *  Output is plain YAML strings; no Kubernetes SDK dependency. */
object K8sManifestGenerator:

  case class K8sConfig(
    namespace:    String               = "default",
    replicas:     Int                  = 2,
    appPort:      Int                  = 8080,
    image:        String               = "app:latest",
    ingressClass: String               = "nginx",
    resources:    ResourceRequirements = ResourceRequirements(),
    annotations:  Map[String,String]   = Map.empty,
    nodeSelector: Map[String,String]   = Map.empty,
    configData:   Map[String,String]   = Map.empty,
    secretData:   Map[String,String]   = Map.empty,
    slot:         Option[String]       = None,   // "blue" | "green" for blue-green
  )

  case class ResourceRequirements(
    cpuRequest:    String = "100m",
    cpuLimit:      String = "1000m",
    memoryRequest: String = "256Mi",
    memoryLimit:   String = "512Mi",
  )

  // ── Effective name (with blue/green slot suffix) ───────────────────────────

  private def effectiveName(name: String, cfg: K8sConfig): String =
    cfg.slot.map(s => s"$name-$s").getOrElse(name)

  // ── Label helpers ──────────────────────────────────────────────────────────

  private def annotationBlock(annotations: Map[String,String], indent: String): String =
    if annotations.isEmpty then ""
    else
      val lines = annotations.map { case (k,v) => s"${indent}$k: $v" }.mkString("\n")
      s"${indent.dropRight(2)}annotations:\n$lines\n"

  private def nodeSelectorBlock(sel: Map[String,String], indent: String): String =
    if sel.isEmpty then ""
    else
      val lines = sel.map { case (k,v) => s"${indent}$k: $v" }.mkString("\n")
      s"${indent.dropRight(2)}nodeSelector:\n$lines\n"

  // ── Deployment ─────────────────────────────────────────────────────────────

  def deployment(name: String, cfg: K8sConfig): String =
    val eName = effectiveName(name, cfg)
    val slotLabel = cfg.slot.map(s => s"\n        slot: $s").getOrElse("")
    s"""|apiVersion: apps/v1
        |kind: Deployment
        |metadata:
        |  name: $eName
        |  namespace: ${cfg.namespace}
        |${annotationBlock(cfg.annotations, "  ")}spec:
        |  replicas: ${cfg.replicas}
        |  selector:
        |    matchLabels:
        |      app: $name${cfg.slot.map(s => s"\n      slot: $s").getOrElse("")}
        |  template:
        |    metadata:
        |      labels:
        |        app: $name$slotLabel
        |    spec:
        |      terminationGracePeriodSeconds: 30
        |${nodeSelectorBlock(cfg.nodeSelector, "      ")}      containers:
        |        - name: $name
        |          image: ${cfg.image}
        |          ports:
        |            - containerPort: ${cfg.appPort}
        |          resources:
        |            requests:
        |              cpu: ${cfg.resources.cpuRequest}
        |              memory: ${cfg.resources.memoryRequest}
        |            limits:
        |              cpu: ${cfg.resources.cpuLimit}
        |              memory: ${cfg.resources.memoryLimit}
        |          livenessProbe:
        |            httpGet:
        |              path: /_health
        |              port: ${cfg.appPort}
        |            initialDelaySeconds: 10
        |            periodSeconds: 10
        |          readinessProbe:
        |            httpGet:
        |              path: /_ready
        |              port: ${cfg.appPort}
        |            initialDelaySeconds: 5
        |            periodSeconds: 5
        |          lifecycle:
        |            preStop:
        |              exec:
        |                command: ["/bin/sh", "-c", "sleep 5"]
        |""".stripMargin

  // ── Service ────────────────────────────────────────────────────────────────

  def service(name: String, cfg: K8sConfig, activeSlot: Option[String] = None): String =
    val slotSelector = activeSlot.map(s => s"\n    slot: $s").getOrElse("")
    s"""|apiVersion: v1
        |kind: Service
        |metadata:
        |  name: $name
        |  namespace: ${cfg.namespace}
        |spec:
        |  selector:
        |    app: $name$slotSelector
        |  ports:
        |    - protocol: TCP
        |      port: 80
        |      targetPort: ${cfg.appPort}
        |  type: ClusterIP
        |""".stripMargin

  // ── Ingress ────────────────────────────────────────────────────────────────

  def ingress(name: String, host: String, cfg: K8sConfig): String =
    s"""|apiVersion: networking.k8s.io/v1
        |kind: Ingress
        |metadata:
        |  name: $name
        |  namespace: ${cfg.namespace}
        |  annotations:
        |    kubernetes.io/ingress.class: ${cfg.ingressClass}
        |spec:
        |  rules:
        |    - host: $host
        |      http:
        |        paths:
        |          - path: /
        |            pathType: Prefix
        |            backend:
        |              service:
        |                name: $name
        |                port:
        |                  number: 80
        |""".stripMargin

  // ── ConfigMap ──────────────────────────────────────────────────────────────

  def configMap(name: String, cfg: K8sConfig): Option[String] =
    if cfg.configData.isEmpty then None
    else
      val dataLines = cfg.configData.map { case (k,v) => s"  $k: \"$v\"" }.mkString("\n")
      Some(s"""|apiVersion: v1
               |kind: ConfigMap
               |metadata:
               |  name: $name-config
               |  namespace: ${cfg.namespace}
               |data:
               |$dataLines
               |""".stripMargin)

  // ── Secret ─────────────────────────────────────────────────────────────────

  def secret(name: String, cfg: K8sConfig): Option[String] =
    if cfg.secretData.isEmpty then None
    else
      val encoded = cfg.secretData.map { case (k,v) =>
        val b64 = java.util.Base64.getEncoder.encodeToString(v.getBytes("UTF-8"))
        s"  $k: $b64"
      }.mkString("\n")
      Some(s"""|apiVersion: v1
               |kind: Secret
               |metadata:
               |  name: $name-secret
               |  namespace: ${cfg.namespace}
               |type: Opaque
               |data:
               |$encoded
               |""".stripMargin)

  // ── Full manifest bundle (for kubectl apply -f -) ─────────────────────────

  def bundle(name: String, cfg: K8sConfig, host: Option[String] = None): String =
    val parts = List(
      Some(deployment(name, cfg)),
      Some(service(name, cfg, cfg.slot)),
      host.map(h => ingress(name, h, cfg)),
      configMap(name, cfg),
      secret(name, cfg),
    ).flatten
    parts.mkString("---\n")

  // ── Blue-green pair ────────────────────────────────────────────────────────

  def blueGreenDeployments(name: String, cfg: K8sConfig, image: String): String =
    val blueCfg  = cfg.copy(slot = Some("blue"),  image = image)
    val greenCfg = cfg.copy(slot = Some("green"), image = image, replicas = 0) // standby starts at 0
    List(deployment(name, blueCfg), deployment(name, greenCfg)).mkString("---\n")
