---
layout: :theme/post
title: "A single dashboard for your cluster network with Network Health"
description: "Network Health in NetObserv: built-in rules, alerts vs recording rules, custom PrometheusRules, and an Istio-based demo surfacing 5xx errors across services."
tags: network,health,observability,prometheus,alerts,recording,rules,dashboard,istio,bookinfo
authors: [lberetta]
---

Understanding the health of your cluster network is not always straightforward.

Issues like packet drops, DNS failures, or policy denials often require digging through multiple dashboards, metrics, or logs before you can even identify where the problem is.

**Network Health in NetObserv aims to simplify this** by surfacing these signals in a single, unified view.

NetObserv now features a dedicated **Network Health** section designed to provide a high-level overview of your cluster's networking status. This interface relies on a set of predefined health rules that automatically surface potential issues by analyzing NetObserv metrics.

Out of the box, these rules monitor several key signals such as:

- **DNS errors and NXDOMAIN responses**
- **packet drops**
- **network policy denials**
- **latency trends**
- **ingress errors**

These built-in rules provide immediate diagnostic value without requiring users to write complex PromQL queries.

But in real-world environments, every application behaves differently. What is considered “healthy” for one workload might not apply to another.

This is where Network Health becomes particularly powerful: it allows you to define **custom health rules** tailored to the specific behavior and expectations of your applications.

The dashboard is organized by scope: **Global**, **Nodes**, **Namespaces**, and **Workloads**. The tab counts show how many items you have in each scope, so you know at a glance where to look.

You can find Network Health in the NetObserv console (standalone or OpenShift at **Observe > Network Traffic**).

The following images describe some health rules in two different scopes:

![Alert rule in Network Health](alert.png)
*Alert rule showing as pending or firing in the dashboard at the Namespace scope*

![Recording rule in Network Health](recording-rule.png)
*Recording rule continuously tracking metric values across severity thresholds at the Global scope*

## Understanding Health Rules: Alerts vs Recording Rules

Behind the scenes, the Network Health section is powered by **PrometheusRule** resources. NetObserv supports two different rule modes, each designed for a different monitoring strategy.

### Alert mode

**Alert rules** trigger when a metric exceeds a defined threshold.

For example: *Packet loss > 10%*

These rules are useful for detecting immediate issues that require action, and they integrate with the existing Prometheus and Alertmanager alerting pipeline. In the Network Health dashboard, alert rules appear when they are **pending** (before the threshold is sustained) or **actively firing**.

### Recording mode

**Recording rules** continuously compute and store metric values in Prometheus without generating alerts.

In the Network Health dashboard, these metrics become visible as soon as the value reaches the lowest configured severity threshold (for example the *info* level). As the value evolves, the rule may move between *info*, *warning*, and *critical* states according to the thresholds defined in its configuration.

Recording rules are particularly useful for:

- continuously monitoring health indicators
- tracking performance trends over time
- reducing alert fatigue

### When to use each

In practice:

- Use **alert rules** when you need to be notified of immediate issues  
- Use **recording rules** when you want continuous visibility into how a metric evolves over time  

For Network Health, recording rules are often a better fit, as they allow you to observe degradation trends before they become critical.

## Health in the topology

Network Health is also integrated with the **Topology** view.

When you select a node, namespace, or workload, the side panel can display a **Health** tab if there are active violations. This allows you to move seamlessly from a high-level signal (for example, “this namespace has DNS issues”) to a contextual view of the affected resources.

![Topology view with health violations](topology.png)
*Topology side panel showing health violations for a selected resource*

## Configuring custom health rules

Custom health rules can be integrated into the Network Health dashboard by creating a **PrometheusRule** resource.

You can define:

- **custom alert rules**, for event-driven detection  
- **custom recording rules**, for continuous visibility  
- or a combination of both  

The way metadata is attached differs between alert and recording rules, as the CRD treats them differently.

### Custom alerts

Alert rules allow annotations directly on each rule. This is where you define:

- `summary`
- `description`
- optionally `netobserv_io_network_health`

The `netobserv_io_network_health` annotation contains a JSON string describing how the signal should appear in the dashboard (unit, thresholds, scope, etc.).

### Custom recording rules

Recording rules do not support annotations at the rule level. Instead, NetObserv requires a single annotation on the **PrometheusRule metadata**:

`netobserv.io/network-health`

This annotation is a JSON object that acts as a map:

- **keys** → metric names (matching the `record:` field)  
- **values** → metadata (summary, description, thresholds, etc.)  

Each recorded metric must have a corresponding entry in this map, as this is how Network Health associates metadata with the metric.

In both cases, you must include the label:

```yaml
netobserv: "true"
```

on both the `PrometheusRule` and each rule’s `labels`.

### Example: custom recording rule

The following example defines a simple recording rule and shows it in the Global tab with custom thresholds:

```yaml
apiVersion: monitoring.coreos.com/v1
kind: PrometheusRule
metadata:
  name: my-recording-rules
  namespace: netobserv
  labels:
    netobserv: "true"
  annotations:
    netobserv.io/network-health: |
      {
        "my_simple_number": {
          "summary": "Test metric (value: {{ $value }})",
          "description": "Numeric value to test thresholds.",
          "netobserv_io_network_health": "{\"unit\":\"\",\"upperBound\":\"100\",\"recordingThresholds\":{\"info\":\"10\",\"warning\":\"25\",\"critical\":\"50\"}}"
        }
      }
spec:
  groups:
    - name: SimpleNumber
      interval: 30s
      rules:
        - record: my_simple_number
          expr: vector(25)
          labels:
            netobserv: "true"
```

While this example is intentionally simple, the same mechanism applies to more complex metrics, including real application signals.

So far, we've looked at how Network Health works and how to extend it.

Let’s now put this into practice with a concrete example.

## Demo: Surfacing service failures with Network Health

Let’s walk through a realistic scenario.

Imagine you're running a microservices application (bookinfo) in your cluster using a service mesh like Istio. Everything looks healthy at first glance, but suddenly users start reporting that some parts of the application are failing intermittently.

Now the question becomes:

> *How do you make this visible at a glance for cluster administrators, without digging into Prometheus queries?*

This is exactly where **Network Health** comes into play.

### Step 1 — Define the health signal

We want to continuously track the **percentage of 5xx errors** affecting services in the application, and surface it directly in the **Network Health dashboard**.

Since we are running with Istio, we can rely on the standard metric:

`istio_requests_total`

This metric is emitted by the **Envoy sidecar proxies**, which means it captures traffic *at the network layer*, independently of the application itself.

In this example, we compute the error rate using the **`reporter="source"`** perspective.

This is an important detail:

- With Istio, metrics can be reported from the **source** or the **destination**
- Using `reporter="source"` ensures we capture **failed requests even when they are not successfully handled by the destination workload** (for example, connection failures, early aborts, or fault injections)

We use the following **recording rule**:

```yaml
apiVersion: monitoring.coreos.com/v1
kind: PrometheusRule
metadata:
  name: bookinfo-service-5xx-network-health
  namespace: bookinfo
  labels:
    netobserv: "true"
  annotations:
    netobserv.io/network-health: |
      {
        "bookinfo_service_5xx_rate_percent": {
          "summary": "Service {{ $labels.destination_service_name }} is generating {{ $value }}% of 5xx errors",
          "description": "Percentage of HTTP 5xx server errors for requests to the {{ $labels.destination_service_name }} service, measured from source reporter over a 5-minute window.",
          "netobserv_io_network_health": "{\"unit\":\"%\",\"upperBound\":\"100\",\"namespaceLabels\":[\"destination_service_namespace\"],\"workloadLabels\":[\"destination_service_name\"],\"recordingThresholds\":{\"info\":\"1\",\"warning\":\"25\",\"critical\":\"90\"}}"
        }
      }
spec:
  groups:
    - name: bookinfo-service-5xx
      interval: 30s
      rules:
        - record: bookinfo_service_5xx_rate_percent
          expr: |
            (
              sum(rate(istio_requests_total{ reporter="source", response_code=~"5.."}[5m])) by (destination_service, destination_service_name, destination_service_namespace)
              /
              sum(rate(istio_requests_total{ reporter="source"}[5m])) by (destination_service, destination_service_name, destination_service_namespace)
              * 100
            )
          labels:
            netobserv: "true"
```

Unlike a service-specific rule, this version does not filter on a single destination.  
Instead, it captures 5xx errors across all services, allowing Network Health to surface multiple affected workloads.

### Step 2 — Simulate a real failure

To reproduce the issue, we inject a fault using Istio.

In this case, we force **100% of requests to the reviews service** to return HTTP 500 errors:

```yaml
apiVersion: networking.istio.io/v1
kind: VirtualService
metadata:
  name: reviews-fault-500
  namespace: bookinfo
spec:
  hosts:
    - reviews
  http:
    - fault:
        abort:
          percentage:
            value: 100
          httpStatus: 500
      route:
        - destination:
            host: reviews
```

Now the application is effectively broken from the user’s perspective.

### Step 3 — Generate traffic

To observe the effect, we generate traffic through the application:

```bash
for i in \{1..100\}; do curl -s http://<bookinfo-url>/productpage > /dev/null; done
```

At this point:

- Requests are flowing through the Istio data plane
- The Envoy proxies are emitting metrics
- All calls to reviews are failing

### Step 4 — Observe Network Health

After a short delay (typically 1–2 minutes), the recording rule is evaluated.

Now, head to Network Health:

You should see:

* The bookinfo namespace marked as critical
* A health indicator showing the 5xx error rate
* The issue surfaced automatically, without querying Prometheus

![Network Health showing 5xx errors](reviews-5xx.png)
*The **bookinfo** namespace marked as critical in Network Health, surfacing a high (up to 100%) percentage of HTTP 5xx errors across services without requiring manual queries.*

### Step 5 — Drill down into the issue

From here, you can:

- Navigate to **Topology** and select the `reviews` service  
- Inspect the health signal in context

![Topology view showing health issue](reviews-5xx-topology.png)
*From Network Health to Topology: selecting the **bookinfo** namespace reveals the same critical 5xx error signal in context.*

This allows you to go from:

> “Something is wrong in this namespace”

to:

> “The affected service can be quickly identified as generating 5xx errors”

in just a few clicks.

## Wrapping it up

We've seen:

- What the Network Health dashboard is and how it surfaces built-in rules (DNS, packet drops, latency, ingress errors, and more).
- The difference between **alert** and **recording** rules, and when to use each.
- How to configure custom health rules (alerts and recording rules) so they appear in the dashboard.
- A **BookInfo** walkthrough: **`PrometheusRule`** with Istio metrics plus **VirtualService** fault injection (**100% / HTTP 500** on **reviews**); **Network Health → Namespaces** marks **bookinfo** as **critical** showing the HTTP 5xx error rate.

Ultimately, Network Health helps bridge the gap between raw metrics and actionable insights, making it easier to understand and troubleshoot network behavior in real time.

As always, you can reach out to the development team on Slack (#netobserv-project on [slack.cncf.io](https://slack.cncf.io/)) or via our [discussion pages](https://github.com/netobserv/netobserv-operator/discussions).
