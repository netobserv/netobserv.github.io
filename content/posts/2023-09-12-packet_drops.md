---
layout: :theme/post
title: "Tracking packet drops"
description: "Network Observability Real-Time Per Flow Packets Drop"
tags: eBPF,Drops,Monitoring,Troubleshooting
authors: [Amoghrd,jpinsonneau,msherif1234]
---

<div class="heading-info">
Check out the revised version of this post on the Red Hat blog: <a href='https://www.redhat.com/en/blog/network-observability-real-time-per-flow-packets-drop'>Network Observability real-time per flow packets drop</a>.
</div>

# Network Observability Real-Time Per Flow Packets Drop

![logo]({page.image('packet-drops/packets-drop-logo.png')})

In OCP ensuring efficient packet delivery is crucial for maintaining smooth
communication between applications. However, due to various factors such
as network congestion, misconfigured systems, or hardware limitations,
packets might occasionally get dropped. Detecting and diagnosing these
packet drops is essential for optimizing network performance and
maintaining a high quality of service.
This is where eBPF (extended Berkeley Packet Filter) comes into play
as a powerful tool for real-time network performance analysis.
In this blog, we'll take a detailed look at how network observability
using eBPF can help in detecting and understanding packet drops,
enabling network administrators and engineers to proactively
address network issues.

## Detecting Packet Drops with eBPF

eBPF enables developers to set up tracepoints at key points within the network
stack. These tracepoints can help intercept packets at specific events,
such as when they are received, forwarded, or transmitted.
By analyzing the events around packet drops, you can gain insight into the
reasons behind them.
In network observability we are using the `tracepoint/skb/kfree_skb` tracepoint hook
to detect when packets are dropped, determine the reason why packets drop and reconstruct
the flow by enriching it with drop metadata such as packets and bytes statistics,
For TCP, only the latest TCP connection state as well as the TCP connection flags
are added.
The packet drops eBPF hook supports TCP, UDP, SCTP, ICMPv4 and ICMPv6 protocols.
There are two main categories for packet drops. The first category, core
subsystem drops,covers most of the host drop reasons; for the complete
list please refer to
[drop-reason](https://github.com/torvalds/linux/blob/master/include/net/dropreason-core.h).
Second, there are OVS-based drops, which is a recent kernel enhancement;
for reference please checkout the following link
[OVS-drop-reason](https://git.kernel.org/pub/scm/linux/kernel/git/netdev/net-next.git/tree/net/openvswitch/drop.h).

## Kernel support

The drop cause tracepoint API is a recent kernel feature only available from RHEL9.2
kernel version. Older kernel will ignore this feature if its configured.

## How to enable packet drops

By default packets drops detection is disabled because it requires
`privileged` access to the host kernel. To enable the feature we need
to create a `FlowCollector` object with the following fields enabled in eBPF config
section

```yaml
apiVersion: flows.netobserv.io/v1beta1
kind: FlowCollector
metadata:
  name: cluster
spec:
  agent:
    type: EBPF
    ebpf:
      privileged: true
      features:
        - PacketDrop
```

## A quick tour in the UI

Once the `PacketDrop` feature is enabled, the OCP console plugin automatically adapts to provide
additional filters and show information across Netflow Traffic page views.

Open your OCP Console and move to
`Administrator view` -> `Observe` -> `Network Traffic` page as usual.

Now, a new query option is available to filter flows by their drop status:

![drop filter query option]({page.image('packet-drops/drop-filter-query-option.png')})

- `Fully dropped` shows the flows that have 100% dropped packets
- `Containing drops` shows the flows having at least one packet dropped
- `Without drops` show the flows having 0% dropped packets
- `All` shows all of the above

Two new filters, `Packet drop TCP state` and `Packet drop latest cause` are available
in the common section:

![drop state & cause filters]({page.image('packet-drops/drop-state-cause-filters.png')})

The first one will allow you to set the TCP state filter:

![state filter]({page.image('packet-drops/state-filter.png')})

- A _LINUX_TCP_STATES_H number like 1, 2, 3
- A _LINUX_TCP_STATES_H TCP name like `ESTABLISHED`, `SYN_SENT`, `SYN_RECV`

The second one will let you pick causes to filter on:

![cause filter]({page.image('packet-drops/cause-filter.png')})

- A _LINUX_DROPREASON_CORE_H number like 2, 3, 4
- A _LINUX_DROPREASON_CORE_H SKB_DROP_REASON name like
 `NOT_SPECIFIED`,`NO_SOCKET`, `PKT_TOO_SMALL`

### Overview

New graphs are introduced in the `advanced options` -> `manage panels` popup:

![advanced options]({page.image('packet-drops/advanced-options.png')})

- Top X flow dropped rates stacked
- Total dropped rate
- Top X dropped state
- Top X dropped cause
- Top X flow dropped rates stacked with total

Select the desired graphs to render them in the overview panel:

![drop graphs 1]({page.image('packet-drops/drop-graphs1.png')})
![drop graphs 2]({page.image('packet-drops/drop-graphs2.png')})
![drop graphs 3]({page.image('packet-drops/drop-graphs3.png')})

Note that you can compare the top drops against total dropped or total traffic
in the last graph using the kebab menu
![drop graph option]({page.image('packet-drops/drop-graph-options.png')})

### Traffic flows

The table view shows the number of `bytes` and `packets` sent in green and the
related numbers dropped in red. Additionally, you can get details about the
drop in the side panel that brings you to the proper documentation.

![drop table]({page.image('packet-drops/drop-table.png')})

### Topology

Last but not least, the topology view displays edges containing drops in red.
That's useful especially when digging on a specific drop reason between two resources.

![drop topology]({page.image('packet-drops/drop-topology.png')})

## Potential use-case scenarios

- `NO_SOCKET` drop reason: There might be packet drops observed due to the
 destination port being not reachable. This can be emulated by running a
 curl command on a node to an unknown port

```bash
while : ; do curl <another nodeIP>:<unknown port>; sleep 5; done
```

The drops can be observed on the console as seen below:

![NO_SOCKET drop table]({page.image('packet-drops/NO-SOCKET-table.png')})

![NO_SOCKET drop overview]({page.image('packet-drops/NO-SOCKET-overview.png')})

- `OVS_DROP_LAST_ACTION` drop reason: OVS packet drops can be observed on
 RHEL9.2 and above. It can be emulated by running the iperf command with
 network-policy set to drop on a particular port.
 These drops can be observed on the console as seen below:

![OVS drop table]({page.image('packet-drops/OVS-table.png')})

![OVS drop topology]({page.image('packet-drops/OVS-topology.png')})

![OVS drop overview]({page.image('packet-drops/OVS-overview.png')})

## Resource impact of using PacketDrop

The performance impact of using PacketDrop enabled with the Network
Observability operator is noticeable on the flowlogs-pipeline(FLP)
component using ~22% and ~9% more vCPU and memory respectively when
compared to baseline whereas the impact on other components in not
significant (less than 3% increase).

## Feedback

We hope you liked this article !

Netobserv is an OpenSource project [available on github](https://github.com/netobserv).
Feel free to share your [ideas](https://github.com/netobserv/network-observability-operator/discussions/categories/ideas), [use cases](https://github.com/netobserv/network-observability-operator/discussions/categories/show-and-tell) or [ask the community for help](https://github.com/netobserv/network-observability-operator/discussions/categories/q-a).
