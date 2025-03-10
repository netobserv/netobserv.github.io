---
layout: :theme/post
title: "Enhancing NetObserv By Introducing Multi Rules Flow filtering capability in eBPF"
description: NetObserv eBPF Flows Filtering
tags: eBPF,Monitoring,Troubleshooting
authors: [msherif1234]
---

# Flow Filtering in eBPF: Optimizing Resource Usage by Selecting Critical Flows

## Introduction

In high-traffic environments, processing every network flow can be resource-intensive,
leading to CPU overload and excessive memory usage.
eBPF-based flow filtering solves this challenge by selecting only important flows,
reducing system strain while maintaining visibility.

## Why Flow Filtering

The primary goal of flow filtering is resource efficiency.
Instead of capturing and analyzing every flow, filtering mechanisms allow us to:

✅ Reduce CPU & Memory Overhead – Process only relevant traffic, avoiding unnecessary computation.

✅ Optimize Storage Usage – Store only meaningful flow records, reducing disk and database load.

✅ Enhance Performance – Minimize packet processing latency and improve system responsiveness.

✅ Focus on Critical Traffic – Prioritize important flows for security, compliance, and performance monitoring.

## How Flow Filtering Works in eBPF

eBPF allows filtering flows at the source, avoiding costly user-space processing. This typically involves:

1- Defining Filtering Rules – Specify criteria such as source/destination IP, port, protocol,
or application metadata.
The following table shows all possible filtering options and their default setting:

| Option      | Description                                              | Possible values                                                         | Default   |
|-------------|----------------------------------------------------------|-------------------------------------------------------------------------|-----------|
| enable      | Enable flow filter                                       | true, false                                                             | false     |
| action      | Action to apply on the flow                              | Accept, Reject                                                          | Accept    |
| cidr        | CIDR to match on the flow                                | for example 1.1.1.0/24 or 1::100/64 or 0.0.0.0/0                        | 0.0.0.0/0 |
| protocol    | Protocol to match on the flow                            | TCP, UDP, SCTP, ICMP, ICMPv6                                            |           |
| direction   | Direction to match on the flow                           | Ingress, Egress                                                         |           |
| destPorts   | Possible options for destination port settings           |                                                                         |           |
|             | Single port to match on the flow                         | for example 80 or 443 or 49051                                          |           |
|             | Range of ports to match on the flow or                   | for example 80-100                                                      |           |
|             | Two ports to match on                                    | for example 80,100                                                      |           |
| sourcePorts | Possible options for source port settings                |                                                                         |           |
|             | Single port to match on the flow                         | for example 80 or 443 or 49051                                          |           |
|             | Range of ports to match on the flow or                   | for example 80-100                                                      |           |
|             | Two ports to match on                                    | for example 80,100                                                      |           |
| ports       | Possible options for destination or source port settings |                                                                         |           |
|             | Single port to match on the flow                         | for example 80 or 443 or 49051                                          |           |
|             | Range of ports to match on the flow or                   | for example 80-100                                                      |           |
|             | Two ports to match on                                    | for example 80,100                                                      |           |
| icmpType    | ICMP type to match on the flow                           | for example 8 or 13                                                     |           |
| icmpCode    | ICMP code to match on the flow                           | for example 0 or 1                                                      |           |
| peerIP      | Peer IP to match on the flow                             | for example 1.1.1.1 or 1::1                                             |           |
| peerCIDR    | Peer IP CIDR to match on the flow                        | for example 1.1.1.1/24 or 1::1/48                                       |           |
| pktDrops    | filter flows with packets drop                           | true, false                                                             |           |
| sampling    | sampling rate to use for filtered flows                  | for example 10 or 20 (any value >= 1)                                   |           |
| tcpFlags    | TCP flags to filter flows by                             | "SYN";"SYN-ACK";"ACK";"FIN";"RST";"URG";"ECE";"CWR";"FIN-ACK";"RST-ACK" |           |

<p style="text-align: center">Table 1: eBPF flow filtering configuration options </p>

Note:

- A rule cannot include both `ports` and either `sourcePorts` or `destPorts` simultaneously.
- The number of eBPF flow filter rules is capped at `16` to maintain efficient memory usage when this feature is enabled.
- Validation webhook rejects rules containing duplicate CIDRs.
- Supports both `IPv4` and `IPv6` formats.
- If users wish to match any CIDR, the default `0.0.0.0/0` (Null CIDR) can be used.

The example below demonstrates various filtering options using multi-rule filters:

```yaml
agent:
  type: eBPF
  ebpf:
    flowFilter:
      enable: true
      rules:
        - action: Accept
          cidr: 10.128.0.0/24
          peerCIDR: 10.129.0.0/24
          ports: '443,6443'
          protocol: TCP
          sampling: 10
        - action: Accept
          cidr: 10.129.0.1/24
          ports: 53
          protocol: UDP
          sampling: 20
        - action: Reject
          tcpFlags: SYN
          cidr: 10.130.0.0/24
          protocol: TCP
          sourcePorts: 80-100
        - action: Accept
          cidr: 172.30.0.0/16
          protocol: SCTP
          pktDrops: true
        - action: Reject
          cidr: 8.8.8.8/32
          protocol: ICMP
          icmpType: 8 // ICMP Echo request packet
```

2- Packet Inspection – Extract relevant packet attributes within an eBPF program.

3- Early Flow Filtering –
Skip or Allow packets based on predefined conditions before doing further ebpf packets processing.
The Following Diagram shows how eBPF filtering is done

<img src="{page.image('ebpf-flow-filtering/ebpf-flow-filtering.png')}" alt="eBPF Flow Filtering Packet Processing">
<p style="text-align: center">Figure 1: eBPF Flow Filtering Packet Processing</p>

eBPF flow filtering uses a specific map type called `BPF_MAP_TYPE_LPM_TRIE`, which enables prefix-based matching.
For more details on LPM maps, refer to [BPF_MAP_TYPE_LPM_TRIE](https://docs.ebpf.io/linux/map-type/BPF_MAP_TYPE_LPM_TRIE/).

When a packet traverses the eBPF stack, its `srcIP` undergoes a longest prefix match lookup in the `filter_map`.
If a specific rule matches, the packet is further evaluated against additional fields,
potentially triggering another longest prefix match for `dstIP`
in the `peer_filter_map` only if peer IP filtering is enabled.
Once all matching criteria are met, the corresponding filter rule action is executed—either
allowing the flow or rejecting it—while updating global flow filtering metrics for debugging purposes.

This process is then repeated with `dstIP` as the primary lookup key in the `filter_map`. If peer IP filtering is enabled,
`srcIP` is checked against the `peer_filter_map`. If a match is found,
the rule’s action is executed, and relevant statistics are updated.

When the `sampling` configuration is specified in a flow filter rule,
the matching flows will use this sampling rate, overriding the rate
configured in the `flowcollector` object.

The dual matching approach ensures bidirectional flow tracking, enabling users to correlate and monitor both
directions of a given flow.

In cases where no matching rules exist, the default behavior is to reject the flow.
However, users can customize the handling of unmatched flows by adding a catch-all entry
`(cidr: 0.0.0.0/0)` and specifying a global action to enforce their preferred policy.

## Key Use Cases

🚀 Reducing Observability Overhead – Avoid logging irrelevant flows in high-traffic Kubernetes clusters.

🔐 Security Filtering – Focus on anomalous or suspicious traffic while ignoring normal flows.

🌐 Network Performance Monitoring – Capture only high-latency or dropped-packet flows for troubleshooting.

### Filter EastWest and NorthSouth flows

Given a cluster with Services subnet on `172.30.0.0/16` and Pods subnet on `10.128.0.0/16` and `10.129.0.0/16`,
we can set specific sampling rates and filters to keep and sample exactly what we want.
For instance, allow traffic to service IP `172.30.100.64:80` with a sampling rate of `10`.
Permit communication between pods in the `10.128.0.0/16` and `10.129.0.0/16` subnets with a sampling rate of `20`.
Also Allow pods within the `10.128.0.0/14` subnet attempt to ping an external IP `8.8.8.8`,
this flow should be allowed with a sampling rate of `30`.
Reject all other traffic `default action`

```yaml
agent:
  type: eBPF
  ebpf:
    flowFilter:
      enable: true
      rules:
        - action: Accept
          cidr: 172.30.0.0/16
          peerCIDR: 10.128.0.0/14
          sampling: 10
        - action: Accept
          cidr: 10.128.0.0/14
          peerCIDR: 10.128.0.0/14
          sampling: 20
        - action: Accept
          cidr: 8.8.8.8/32
          sampling: 30
          peerCIDR: 10.128.0.0/14
          protocol: ICMP
          icmpType: 8
```

<img src="{page.image('ebpf-flow-filtering/ebpf-svc-and-pods-flows.png')}" alt="eBPF Flow Filtering Kubernetes NorthSouth and EastWest Flows">
<p style="text-align: center">Figure 2: eBPF Flow Filtering Kubernetes NorthSouth and EastWest Flows</p>

### Filter flows with packet drops

Let's filter Kubernetes service flows that include a packet drop and discard all others.
For this use case, the `PacketDrop` feature must be enabled with eBPF in `privileged` mode,
as demonstrated in the configuration below.

```yaml
agent:
  type: eBPF
  ebpf:
    privileged: true
    features:
      - PacketDrop
    flowFilter:
      enable: true
      rules:
        - action: Accept
          cidr: 172.30.0.0/16
          pktDrops: true
        - action: Reject
          cidr: 0.0.0.0/0
```

<img src="{page.image('ebpf-flow-filtering/ebpf-filter-svc-pkt-drops.png')}" alt="eBPF Flow Filtering Kubernetes Services with Packet Drop">
<p style="text-align: center">Figure 3: eBPF Flow Filtering Kubernetes Services with Packet Drop</p>

### Filter TCP flows using TCP Flags

Filtering based on TCP flags is an effective method to detect and mitigate TCP SYN flood attacks in a cluster.
A SYN flood is a Denial-of-Service (DoS) attack where an attacker overwhelms a target system by sending a large
number of SYN packets without completing the three-way handshake,
depleting system resources and disrupting legitimate connections.

```yaml
agent:
  type: eBPF
  ebpf:
    flowFilter:
      enable: true
      rules:
        - action: Accept
          cidr: 0.0.0.0/0
          protocol: TCP
          tcpFlags: SYN
          sampling: 1
```

<img src="{page.image('ebpf-flow-filtering/ebpf-filter-with-tcpflags.png')}" alt="eBPF Flow Filtering TCP flows using TCP flags">
<p style="text-align: center">Figure 4: eBPF Flow Filtering TCP flows using TCP flags</p>

### Filter DNS query over ports 53 and 5353 for both TCP and UDP

This Use case involves capturing DNS flows over both `TCP` and `UDP`,
with the option to enable the `DNSTracking` feature for enhanced DNS latency insights.

```yaml
agent:
  type: eBPF
  ebpf:
    features:
      - DNSTracking
    flowFilter:
      enable: true
      rules:
        - action: Accept
          cidr: 0.0.0.0/0
          sourcePorts: '53,5353'
          sampling: 1
```

<img src="{page.image('ebpf-flow-filtering/ebpf-filter-DNS-flows.png')}" alt="eBPF Flow Filtering DNS flows">
<p style="text-align: center">Figure 5: eBPF Flow Filtering DNS flows</p>

## Conclusion

By filtering flows at the kernel level with eBPF, we maximize efficiency,
ensuring only the most relevant data is processed and stored.
This approach is critical for scalability, cost reduction, and real-time network insights.

## Feedback

We hope you liked this article!
Netobserv is an open source project [available on github](https://github.com/netobserv).
Feel free to share your [ideas](https://github.com/orgs/netobserv/discussions/categories/ideas), [use cases](https://github.com/orgs/netobserv/discussions/categories/show-and-tell) or [ask the community for help](https://github.com/orgs/netobserv/discussions/categories/q-a).
