---
title: About
description: |
  NetObserv aka Network Observability is a set of components used to observe network traffic by generating NetFlows from eBPF agents, enrich those flows using a configurable pipeline that is Kubernetes-aware, export them in various ways (logs, metrics, Kafka, IPFIX...), and finally provide a comprehensive visualization tool for making sense of that data, and a CLI. Those components can be used as standalones or deployed in Kubernetes / OpenShift via an integrated Operator.
layout: :theme/page
---

# About NetObserv

NetObserv is a set of components used to observe network traffic by generating NetFlows from [eBPF agents](https://github.com/netobserv/netobserv-ebpf-agent), enriching those flows using [a configurable pipeline](https://github.com/netobserv/flowlogs-pipeline/) which is Kubernetes-aware, exporting them in various ways (logs, metrics, Kafka, IPFIX...), and finally providing a comprehensive [visualization tool](https://github.com/netobserv/network-observability-console-plugin/) for making sense of that data, and [a CLI](https://github.com/netobserv/network-observability-cli). Those components can be used as standalones or deployed in Kubernetes / OpenShift via an [integrated Operator](https://github.com/netobserv/network-observability-operator/).

It is known and distributed in Red Hat OpenShift as the [Network Observability operator](https://docs.openshift.com/container-platform/latest/observability/network_observability/network-observability-operator-release-notes.html).

## Topology view

![Topology]({page.image('about/topology-main.png')})

## Traffic flows view

![Traffic flows]({page.image('about/network-traffic-main.png')})

## Overview dashboard

![Overview charts]({page.image('about/overview-dashboard.png')})

# Blog authors

<div class="authors">
  <!-- authors.yml is in the data/ -->
  {#for id in lists:shuffle(cdi:authors.fields)}
    {#let author=cdi:authors.get(id)}
    <!-- the author-card tag is defined in the default Roq theme -->
    {#author-card name=author.name avatar=author.avatar nickname=author.nickname profile=author.profile}
      {#if author.bio}
        {author.bio}
      {/if}
    {/author-card}
  {/for}
</div>
