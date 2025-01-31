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

## Why Flow Filtering?

The primary goal of flow filtering is resource efficiency.
Instead of capturing and analyzing every flow, filtering mechanisms allow us to:

✅ Reduce CPU & Memory Overhead – Process only relevant traffic, avoiding unnecessary computation.

✅ Optimize Storage Usage – Store only meaningful flow records, reducing disk and database load.

✅ Enhance Performance – Minimize packet processing latency and improve system responsiveness.

✅ Focus on Critical Traffic – Prioritize important flows for security, compliance, and performance monitoring.

## How Flow Filtering Works in eBPF
eBPF allows filtering flows at the source, avoiding costly user-space processing. This typically involves:

1️⃣ Defining Filtering Rules – Specify criteria such as source/destination IP, port, protocol, 
or application metadata, The following table shows all possible filtering options and their default setting

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
| peerCIDR    | Peer IPCIDR to match on the flow                         | for example 1.1.1.1/24 or 1::1/48                                       |           |
| pktDrops    | filter flows with packets drop                           | true, false                                                             |           |
| sampling    | sampling rate to use for filtered flows                  | for example 10 or 20 (any value >= 1)                                   |           |
| tcpFlags    | TCP flags to filter flows by                             | "SYN";"SYN-ACK";"ACK";"FIN";"RST";"URG";"ECE";"CWR";"FIN-ACK";"RST-ACK" |           |


Note:

- You can't use ports and either sourcePorts or destPorts in the same rule.

The Following configuration example shows some of the possible filtering options with multi rules filters

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
2️⃣ Packet Inspection – Extract relevant packet attributes within an eBPF program.

3️⃣ Early Flow Filtering –
Skip or Allow packets based on predefined conditions before doing further ebpf packets processing.
The Following Diagram shows how eBPF filtering is done

<img src="{page.image('ebpf-flows-filtering/ebpf-flows-filtering.png')}" alt="eBPF Flows Filtering Processing">

Note:
- The maximum number of eBPF flow filter rules is limited to 16 to ensure conservative memory
usage when this feature is enabled.

- rules with duplicate CIDRs are rejected via validation webhook.

- Both IPv4 and IPv6 formats are supported.

- In case users wanted to match on any CIDR, default Null CIDR `0.0.0.0/0` can be used.

## Key Use Cases

🚀 Reducing Observability Overhead – Avoid logging irrelevant flows in high-traffic Kubernetes clusters.

🔐 Security Filtering – Focus on anomalous or suspicious traffic while ignoring normal flows.

🌐 Network Performance Monitoring – Capture only high-latency or dropped-packet flows for troubleshooting.

### Filter EastWest and NorthSouth flows

Let's allow serviceIP 172.30.100.64:80 with sampling 10
and allow pods traffic between subnet 10.128.0.0/16 and 10.129.0.0/16 with sampling 20 and reject everything else

```yaml
agent:
  type: eBPF
  ebpf:
    flowFilter:
      enable: true
      rules:
        - action: Accept
          cidr: 172.30.0.0/16
          sampling: 10
        - action: Accept
          cidr: 10.128.0.0/16
          peerCIDR: 10.129.0.0/16
          sampling: 20
        - action: Reject
          cidr: 0.0.0.0/0
``` 

<img src="{page.image('ebpf-flows-filtering/ebpf-svc-and-pods-flows.png')}" alt="eBPF Flows Filtering Kubernetes NorthSouth and EastWest Flows">

### Filter flows with packet drops

let's filter any kubernetes service flows with a packet drop and reject everything else,
Please note for this use case `PacketDrop` feature
must be enabled with eBPF in `privileged` mode as shown in the below configuration

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

<img src="{page.image('ebpf-flows-filtering/ebpf-filter-svc-pkt-drops.png')}" alt="eBPF Flows Filtering Kubernetes Services with Packet Drop">

### Filter TCP flows with TCP Flags detect TCP SYN Flood attack

Using TCP Flags filtering can help detect when your cluster is under TCP Syn Flood attack

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

<img src="{page.image('ebpf-flows-filtering/ebpf-filter-with-tcpflags.png')}" alt="eBPF Flows Filtering TCP flows using TCP flags">

### Filter DNS query over ports 53 and 5353 for both TCP and UDP

This Use case focuses on capturing DNS flows over both TCP and UDP
while optionally enabling the `DNSTracking` feature to facilitate DNS latency enrichment.

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

<img src="{page.image('ebpf-flows-filtering/ebpf-filter-DNS-flows.png')}" alt="eBPF Flows Filtering DNS flows">

## Conclusion

By filtering flows at the kernel level with eBPF, we maximize efficiency,
ensuring only the most relevant data is processed and stored.
This approach is critical for scalability, cost reduction, and real-time network insights.

## Feedback

We hope you liked this article!
Netobserv is an OpenSource project [available on github](https://github.com/netobserv).
Feel free to share your [ideas](https://github.com/netobserv/network-observability-operator/discussions/categories/ideas), [use cases](https://github.com/netobserv/network-observability-operator/discussions/categories/show-and-tell) or [ask the community for help](https://github.com/netobserv/network-observability-operator/discussions/categories/q-a).
