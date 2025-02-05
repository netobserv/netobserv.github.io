---
layout: :theme/post
title: "Performance improvements in 1.8"
description: "How we reached -50% CPU on the eBPF agent"
tags: eBPF,Performance,Code
authors: [jotak]
---

_Thanks to: Julien Pinsonneau, Sara Thomas, Steven Lee and Mehul Modi for reviewing_

Last year, 2024, there were a few discussions at Red Hat, R&D related, around the eBPF Agent provided with NetObserv. One of these discussions focused especially on its performances and was a starting point to bring life to ideas. I'll take this opportunity to warmly thank Simone Ferlin-Reiter, Toke Høiland-Jørgensen, Mohamed S. Mahmoud and Donald Hunter for their contributions. Toke took the time to deep-dive in the code and shared his thoughts on the potential improvements.

The [eBPF agent](https://github.com/netobserv/netobserv-ebpf-agent/) is the base block from where everything starts in NetObserv, as such it is a critical piece to optimize. That doesn't mean the other pieces should be neglected. [FLP](https://github.com/netobserv/flowlogs-pipeline), be prepared: you're next!

We will dive into the implementation details later and, for once, start with the results.

## Show the numbers!

As part of our CI processes and tools, we use [kube-burner](https://github.com/kube-burner/kube-burner) to measure performance and detect regressions. It's almost the same test beds that we already mentioned [in a previous post](https://netobserv.io/posts/light-weight-network-observability-operator-without-loki/), with some slight modifications: from 3 test beds — the 25-nodes, the 65-nodes and the 120-nodes ones, with only the 65-nodes that was stress-testing ingress — we moved to just 2 test beds: 

- Test bed 1 uses a 25-nodes cluster that runs [node-density-heavy](https://github.com/kube-burner/kube-burner-ocp?tab=readme-ov-file#node-density-heavy) and [ingress-perf](https://github.com/cloud-bulldozer/ingress-perf) workloads. It has around 5200 pods in 81 namespaces.
- Test bed 2 uses a bigger 250-nodes cluster, running [cluster-density-v2](https://github.com/kube-burner/kube-burner-ocp?tab=readme-ov-file#cluster-density-v2) and ingress-perf workloads. It has around 14K pods in 1000 namespaces.

NetObserv is configured with 1:1 sampling, thus processing every packet.

Here's how the CPU metrics compare between NetObserv 1.7 and 1.8:

**Fig. 1: eBPF agent user-space CPU cores usage, averaged per node**

![User-space CPU]({page.image('perf-improvements-1-8/user-space-cpu.png')})

The drop is quite impressive, between -40% and -57%. If we consider all the NetObserv dependencies, which include Flowlogs-pipeline, Loki and Kafka, the decrease ranges between -11% and -25%.

As always in this kind of exercise, it's important to call out that your mileage may vary. Many factors can influence the measurements, such as the total bandwidth, the pods density, the number of inter-connections, the nature of the traffic, etc.

What about the kernel space? As of today, we don't yet have very mature metrics for that (we will work on it), and I have been using [bpftop](https://github.com/Netflix/bpftop) to capture that manually. It's not super accurate, but we had consistent enough observations to deduce something useful. Bpftop gives a CPU percentage. The tests that I ran here are also different, they're based on [hey](https://github.com/rakyll/hey), with my Kubernetes-ready wrapper called [hey-ho](https://github.com/jotak/hey-ho), all running on a much smaller 6-nodes cluster, with more or less load injected per node.

Here I used two profiles, or scenarios:

- Medium load: 200 distributed workers rate-limited at 50 qps
- High load: 900 distributed workers rate-limited at 50 qps

**Fig. 2: eBPF agent kernel-space CPU percentage, on a single node**

![Kernel-space CPU]({page.image('perf-improvements-1-8/kernel-space-cpu.png')})

It's maybe less impressive, but is still _circa_ -20%, which can make a difference. In fact, it _does_ make a difference, because we have seen the overall cluster throughput slightly increase with these changes in our Test bed 1 (which is more stressed than Test bed 2 in terms of ingress traffic per node). To put it differently, NetObserv shows less overhead in stressed situations. More on that later.

**Fig. 3: Total cluster traffic in Test bed 1**

![Total traffic]({page.image('perf-improvements-1-8/total-traffic.png')})

However, there's a catch: more traffic means... more flows to observe. In some ways, we get hit by our improvements. While we observed +18% traffic in the cluster, there's a parallel +11% memory increase in NetObserv because there is more to observe. It can be mitigated in multiple ways, such as with sampling, or by configuring the new filtering and conditional sampling options that we are also adding in this release.

The performance enhancements also impact the kernel memory usage. As we'll see later, we did several changes on the BPF maps that optimize the memory used. We observed between -20% and -60% of the eBPF objects size in the hey-based tests (however, note that these tests don't focus on stressing memory).

**Fig. 4: eBPF agent kernel-space memory usage, on a single node**

![Kernel-space memory]({page.image('perf-improvements-1-8/kernel-space-memory.png')})

_NB: the "wide load" profile used here corresponds to 3000 workers rate-limited at 2 qps, using a more distributed pattern across pods/nodes/namespaces._

### Overhead

Some resource overhead is inevitable when observing traffic, but hopefully NetObserv makes it as low as possible. By lowering the CPU usage, we expect to lower the overhead: less CPU used by the eBPF agent means more CPU available for other tasks. When running low on CPU, it can make a difference.

I used _hey_ again to measure the overhead in two different ways:

- in terms of added latency
- in terms of maximum throughput

After stress-testing a target, _hey_ provides statistics such as latency percentiles, min/max/average, and the number of queries per second. It can be configured with rate limits, but for the purpose of this test I didn't use it. It's only limited by the number of workers, which is set to 50. I am also running a rate-limited _hey-ho_ in parallel, just to generate some background noise and warm NetObserv.

Here is the output of the baseline run, without NetObserv:

```
Summary:
  Total:	30.0017 secs
  Slowest:	0.1207 secs
  Fastest:	0.0006 secs
  Average:	0.0015 secs
  Requests/sec:	39863.3677
  
  Total data:	735520320 bytes
  Size/request:	735 bytes

Response time histogram:
  0.001 [1]	|
  0.013 [999261]	|■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■
  0.025 [538]	|
  0.037 [70]	|
  0.049 [32]	|
  0.061 [38]	|
  0.073 [9]	|
  0.085 [1]	|
  0.097 [0]	|
  0.109 [0]	|
  0.121 [50]	|

Latency distribution:
  10% in 0.0008 secs
  25% in 0.0009 secs
  50% in 0.0011 secs
  75% in 0.0014 secs
  90% in 0.0018 secs
  95% in 0.0023 secs
  99% in 0.0038 secs

Details (average, fastest, slowest):
  DNS+dialup:	0.0000 secs, 0.0000 secs, 0.0044 secs
  DNS-lookup:	0.0000 secs, 0.0000 secs, 0.0048 secs
  req write:	0.0000 secs, 0.0000 secs, 0.0082 secs
  resp wait:	0.0014 secs, 0.0006 secs, 0.1193 secs
  resp read:	0.0001 secs, 0.0000 secs, 0.0223 secs

Status code distribution:
  [200]	1000000 responses
```

After that I ran the same command twice, first with NetObserv 1.7, second with NetObserv 1.8.

In both cases, some values remained unchanged compared to the baseline: Fastest was always 0.0006s, Average 0.0015s, and same for the Fastest and Average details (DNS+dialup, DNS-lookup, req write, resp wait, resp read). These numbers are a good sign that NetObserv doesn't generate latency overhead for most of the requests. It's on higher percentiles that an overhead starts showing up.

**Fig. 5: latency distribution across baseline and versions (in seconds)**

![Latency distribution]({page.image('perf-improvements-1-8/latency-distribution.png')})

Unlike in 1.7, at p50 1.8 shows no latency overhead compared to the baseline, meaning that half of the requests have pretty much no overhead. At p99, the latency overhead in 1.7 was +1 millisecond, in 1.8 it decreased to +0.7 milliseconds.

It's also in terms of maximum queries per second that the overhead is interesting to see.

**Fig. 6: QPS across baseline and versions**

![QPS]({page.image('perf-improvements-1-8/qps.png')})

Since this is not rate-limited, _hey_ generates as much load as it can with its 50 workers. It shows a QPS increase of +5.6% compared to NetObserv 1.7.

## How did we go there: under the cover

Now is the technical part. Here's what we did.

### Moving away from per-CPU map

eBPF provides several [structures](https://docs.ebpf.io/linux/map-type/) that allow sharing data between the kernel space and the user space. Hash maps are especially useful as they allow for data persistence across calls and hook points. Thanks to hash maps, NetObserv can keep track of flows in the kernel space, aggregate new packets observed there, and thus limit the size of data transfer between the kernel and the user spaces for better performance.

Among the different map types, there is [BPF_MAP_TYPE_HASH](https://docs.ebpf.io/linux/map-type/BPF_MAP_TYPE_HASH/) and [BPF_MAP_TYPE_PERCPU_HASH](https://docs.ebpf.io/linux/map-type/BPF_MAP_TYPE_PERCPU_HASH/).

**Fig. 7: comparison between BPF_MAP_TYPE_HASH and BPF_MAP_TYPE_PERCPU_HASH**

![Map types]({page.image('perf-improvements-1-8/map-types.png')})

They are quite similar, except that the latter has, for every key, one value per CPU. In other words, data processed by different CPUs lands in different buckets. When it comes to observing packets, you may think there is no need for the per-CPU map; since we want to aggregate packets into network flows, the per-CPU segregation doesn't make sense, and we don't care about which CPU processed the packet. Of course, we would need to handle concurrent access: if two packets are processed by two CPUs, there could be a concurrent write if they relate to the same flow. [Spin locks](https://docs.ebpf.io/linux/concepts/concurrency/#spin-locks) are there for this reason.

But the crux of the matter was that our map is accessed for writes by different hook points. For instance, there is the [TC hook](https://github.com/netobserv/netobserv-ebpf-agent/blob/2c96c420fc5ed223b7b99b00b2705fe84c5c0110/bpf/flows.c#L282) that is our main observation point for capturing packets, and other trace points such as [the one looking for drops](https://github.com/netobserv/netobserv-ebpf-agent/blob/2c96c420fc5ed223b7b99b00b2705fe84c5c0110/bpf/pkt_drops.h#L104), which enriches the flows with additional information. Those enrichment trace points can't acquire the lock to perform updates. Using a per-CPU map has been an answer to this problem, as it is not going to be written concurrently.

The drawback of using per-CPU maps for network flows is that for the same flow, which is defined (roughly — more on that later) by its 5-tuple (source IP, destination IP, source port, destination port, protocol), packets have no reason to be processed by just a single CPU. In other words, the flow information is spread over multiple CPU buckets in the map. Not only does it force us to reassemble flow chunks when they are read from the user space, wasting precious CPU cycles; but most importantly, it's not memory efficient, with map sizes being potentially multiplied by the number of cores, and data being sometimes duplicated on several CPU buckets.

Today, we change all of that by refactoring our data structures. Our solution is to avoid writing the flow map from the enrichment hooks. Instead of that, we are introducing a new map, dedicated to enriched data. Map keys are going to be duplicated across those two maps, but it's a lesser evil. So now, we can change the main map to be a shared one across CPUs, with a spinlock. We still have some reassembling work to do in the user space though, to merge the main map with the enrichment map, but it is more straightforward than merging entire flows together. We also have a couple of ideas to further improve this process, more on that later.

Splitting data between a main map and an enrichment map has another benefit: when no enrichment is needed (e.g. when none of the [agent features](https://github.com/netobserv/network-observability-operator/blob/main/docs/FlowCollector.md#flowcollectorspecagentebpf-1) are enabled), no memory is allocated for them, resulting — again — in a more efficient memory usage.

This is for a large part what triggered the memory improvement mentioned above:

**Fig. 4 (again): eBPF agent kernel-space memory usage, on a single node**

![Kernel-space memory]({page.image('perf-improvements-1-8/kernel-space-memory.png')})

### Shrinking map keys and de-duplication

One of the challenges when observing traffic is de-duplication. As NetObserv allows capturing traffic from any network interface, there are good chances that the same packet is going to be seen several times, especially in the context of software-defined networks. We don't want it to result in the creation of several flows, both for performance reasons (more flows mean more memory, CPU and storage) and for metrics correctness (to avoid double counts when measuring traffic bandwidth at aggregated levels).

Previously, we have been doing this de-duplication in the user space. That is to say, our flows in kernel were identified not truly by their 5-tuple as mentioned previously, but by a larger [12-tuple](https://github.com/netobserv/netobserv-ebpf-agent/blob/c7c2acc6ac36c0c9966c67f668e29995ceb40066/bpf/types.h#L117-L137) that also included the direction (ingress/egress), the interface index, the MAC addresses (to be honest, I can't remember why the MAC addresses were there) and a couple of other fields. That resulted in flows being created in the BPF map for each different traversed interface, increasing the overall map cardinality. The flows were later de-duplicated in the user space, involving a temporary cache, at a cost of CPU and memory. Why did we do it that way? It's again related to the per-CPU map. De-duplication cannot be done properly in the kernel if the CPU processing a packet doesn't have the full picture of the related flow. It needs to know on which interface a packet was first seen, to decide whether to increase counters or not. The user space was the only place having that full picture.

By removing the per-CPU map, we unlock this next improvement: shrinking the map key to a lighter [7-tuple](https://github.com/netobserv/netobserv-ebpf-agent/blob/2c96c420fc5ed223b7b99b00b2705fe84c5c0110/bpf/types.h#L159-L167), performing the de-duplication directly in-kernel, resulting in reducing the map size, the CPU cost to read it, and getting rid of the whole user-space mechanism for de-duplication. Why 7-tuples, by the way? Well, it's only for some ICMP related stuff — not something to worry about, performance-wise, as ICMP traffic is generally negligible (unless your cluster is full of `pings` running everywhere!) and has a smaller flow cardinality (no ports). The most important part was to remove the interface index from the key.

One of the consequences is that we would lose information about each traversed interface. We want to still be able to tell: this sample flow went through `genev_sys_6081`, then `8aa5bd1d532fca8@if2`, and finally `ens5`. So we had to add this information as an array in the map values.

Less flows to fetch thanks to smaller keys, and no user-space deduplication: less used CPU.

**Fig. 1 (again): eBPF agent user-space CPU cores usage, averaged per node**

![User-space CPU]({page.image('perf-improvements-1-8/user-space-cpu.png')})

### Other improvements

There have been other improvements identified, more as low-hanging fruits, but still impactful:

- Removing some redundant calls to `bpf_map_update()`
- Removing `packed` attribute from the eBPF structures

## Side effects

Those changes triggered a refactoring that doesn't come without consequences and tradeoffs.

- Most importantly, **partial flows**: because now having two different maps, one for the main flows, generated from the TC hook, and another for the enrichments, generated from other hooks, there can sometimes be a mismatch between these two. Especially when the enrichment map keys aren't found in the flows map, the result is a generated partial flow, which is a flow that lacks some information, namely the TCP flags, the MAC addresses, and the bytes and packets counters. It doesn't mean these values are entirely lost; you could still be able to find them in an adjacent flow — it's because flows are evicted periodically, and an eviction might occur precisely at the _wrong moment_ (it's a race condition), with only partial data being available at that time. Another cause for partial flows is when the agent is configured with a sampling rate greater than one. If, for some reason, the enrichment data is sampled but the corresponding TC hook packet isn't, this would also result in a partial flow.

**Fig. 8: an example of partial flow, with 0 bytes/packets**

![Partial flow]({page.image('perf-improvements-1-8/partial-flow.png')})

- Limitation in **observed interfaces**: because BPF structure size must be predictable, we cannot store all the observed interfaces in the map. We need to set a maximum, which is currently six. If a packet is seen on more than six interfaces, we would only show the first six. Today we consider it sufficient, but we might raise the max later if needed. A Prometheus metrics was added to notify for the maximum reached.

## Next

Better performance is a never ending battle. In NetObserv, it is critical to run our eBPF probes with the minimal possible impacts on the monitored workloads and on the cluster overall.

### Back to the ringbuffer

As mentioned above, there are some ideas to improve further the data processing between kernel and user spaces. One of them consists in re-evaluating the relevance of a [ringbuffer](https://docs.ebpf.io/linux/map-type/BPF_MAP_TYPE_RINGBUF/).

[Previously](https://opensource.com/article/22/8/ebpf-network-observability-cloud) we found that a hash map is more relevant than a ringbuffer for forwarding flows to the user space. This is mostly because the hash map allows in-kernel aggregation, thus reducing the size of the data to copy, whereas the ringbuffer is for a stateless usage, transferring data as soon as it comes, thus with the risk to copy some duplicated parts again and again.

However, now that we have split the data into two maps, while the above certainly remains true for the main flows map, we can't say the same for the enrichment map. Enriched data is less subject to in-kernel aggregation, and the enrichment specialized hooks are less often triggered. So it's surely worth it to re-evaluate in that context.

### Even more de-duplication

We've seen that doing de-duplication in the kernel already helped a lot. There's more that we can do, but to keep nothing from you, it's a bit tricky.

Modern networking is complex and multi-layered. There are more layers than what we currently de-duplicate. In OVN-Kubernetes for instance, a [GENEVE encapsulation](https://www.redhat.com/en/blog/what-geneve) is used for sending packets between nodes. When it happens, NetObserv sees different things. There's the pod-to-pod traffic, for instance, that is identified with the corresponding Pod IPs. That's one flow. And at the same time there's the node-to-node traffic, which is seen from another interface like `br-ex` and identified with the source and destination Node IPs. Two flows, same packet (encapsulated or not).

On top of that, there are also the Kubernetes Services that show up with different IPs — so, different flows: remember that a flow is defined by its IPs — even if that's for the same packet. This is resolved through NAT.

The question remains open whether we want to de-duplicate that or not. It may impact not only the eBPF agent, but also how things are displayed in the Console plugin, as we'll need to offer a more layered view of the flows. So it is also a UX challenge. Note that very recently, we also started to cover Service NAT by a different means, through [conntrack](https://netobserv.io/posts/enhancing-netobserv-for-kubernetes-service-flows-using-ebpf/). But that doesn't actually *de-duplicate*, it's an enrichment.

Why would it be tricky to go further? It involves tracing packets across the networking stack, and there's currently no easy way to do it, as far as I can tell. Not only do we need to detect whether a packet was already seen or not, but we also need to keep a reference to its corresponding flow, since we cannot just retrieve it from the observed IPs, as they change.

There are tools that do a part of that already, such as [retis](https://github.com/retis-org/retis), but with a complex approach that might not be easily transposable to NetObserv without extra overhead.

What about this dedicated `mark` field in the [Linux SKB](https://docs.ebpf.io/linux/program-context/__sk_buff/#mark)? Well, not only it is quite [overloaded](https://github.com/fwmark/registry) (risking incompatibility with other softwares), but its 32-bits wouldn't be sufficient to store our flows references. Last but not least, this field is sometimes reset throughout a packet lifetime.

The other track that we are exploring is doing packet finger-printing (with a [PoC here](https://github.com/jotak/netobserv-agent/blob/poc-tracing/bpf/dedup.h#L113)) to identify packets uniquely so we can detect whether they were already seen or not, and which flow they belong to. Early tests look somewhat good, but there are always risks of false-positives and false-negatives, which would generate inaccurate data, such as dismissing legitimate flows because we would wrongly think they are duplicate. Because of its potential unsoundness, we would be reluctant to make it part of the core NetObserv product, but it could be proposed as an experimental feature, at least until it proves being robust.

What would really help us is if there was a writable [SKB](https://docs.kernel.org/networking/skbuff.html) region for custom metadata, similar to [what exists for XDP](https://docs.kernel.org/networking/xdp-rx-metadata.html), allowing us to store our flow keys. It would be a more efficient and robust solution. Hopefully, [it might come at some point](https://lpc.events/event/18/contributions/1935/), and we're looking forward to it.

## Hope you'll like it

With these performance improvements, we hope it's going to lower the barrier for network observability adoption — more than ever, NetObserv awaits you — and will come as good news for everyone already using it. Speaking of resource consumption, I briefly mentioned the new filtering & sampling features that also come in 1.8: they allow you to fine-tune what you want to observe or not, and at which sampling ratio. This will be the occasion of another article.
