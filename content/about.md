---
title: About
description: |
  NetObserv aka Network Observability is a set of components used to observe network traffic by generating "NetFlows" from eBPF agents, enrich those flows using a configurable pipeline that is Kubernetes-aware, export them in various ways (logs, metrics, Kafka, IPFIX...), and finally provide a comprehensive visualization tool for making sense of that data, and a CLI. Those components can be used as standalones or deployed in Kubernetes / OpenShift via an integrated Operator.
layout: :theme/page
---

# About NetObserv

NetObserv (or Network Observability) is a set of components used to observe network traffic by generating "NetFlows" from [eBPF agents](https://github.com/netobserv/netobserv-ebpf-agent), enriching those flows using [a configurable pipeline](https://github.com/netobserv/flowlogs-pipeline/) which is Kubernetes-aware, exporting them in various ways (logs, metrics, Kafka, IPFIX...), and finally providing a comprehensive [visualization tool](https://github.com/netobserv/network-observability-console-plugin/) for making sense of that data, and [a CLI](https://github.com/netobserv/network-observability-cli). Those components can be used as standalones or deployed in Kubernetes / OpenShift via an [integrated Operator](https://github.com/netobserv/network-observability-operator/).
