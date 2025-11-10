---
layout: :theme/post
title: "Identifying cluster external traffic with subnet labels"
description: "Check how NetObserv can help you understanding the traffic to cluster-external workloads and services"
tags: subnet,labels,cardinality,external,metrics,flowmetrics
authors: [jotak]
---

_Thanks to: (placeholder) for reviewing_

Often times, people who are installing NetObserv are especially looking for a solution that monitors the traffic from and to the cluster. For them, in-cluster traffic monitoring only comes as a secondary consideration. NetObserv does not process external traffic in any particular way, by default, internal and external traffic are just regular network traffic, period. 

The eBPF agents know nothing about the network topology. They see packets, extract the IP addresses and metadata, do some aggregation, and forward that to `flowlogs-pipeline`. They can operate in various contexts, even though we're mostly interested in Kubernetes here, you can run them on your Linux PC if you wish, they're context-agnostic.

`flowlogs-pipeline` is context-aware, depending on its configuration. It's the one that knows about Kubernetes, and it uses that knowledge to enrich the network flows with pod names, namespaces, and so on.

But again, it doesn't know with absolute certainty what IP should be considered cluster-internal, and what should be cluster-external. It's actually the NetObserv operator that can provide this information based on the `FlowCollector` configuration, via `spec.processor.subnetLabels`.

## What's subnet labels?

As per [the doc](https://github.com/netobserv/network-observability-operator/blob/main/docs/FlowCollector.md#flowcollectorspecprocessorsubnetlabels), subnet labels "allow to define custom labels on subnets and IPs or to enable automatic labelling of recognized subnets in OpenShift, which is used to identify cluster external traffic. When a subnet matches the source or destination IP of a flow, a corresponding field is added: `SrcSubnetLabel` or `DstSubnetLabel`."

In OpenShift, NetObserv will check the Cluster Network Operator configuration to know which CIDRs are configured for Pods, Services and Nodes. You can verify that:

```bash
# "network" resource is from the cluster network operator configuration
$ kubectl get networks cluster -ojsonpath='{.spec}' | jq
{
  (...),
  "clusterNetwork": [
    {
      "cidr": "10.128.0.0/14",
      "hostPrefix": 23
    }
  ],
  "serviceNetwork": [
    "172.30.0.0/16"
  ]
}

# now, the resulting config map for flowlogs-pipeline
$ kubectl get cm -n netobserv flowlogs-pipeline-config -ojsonpath='{.data.config\.json}' | jq '.parameters[1].transform.network.subnetLabels'
[
  {
    "cidrs": [
      "10.128.0.0/14"
    ],
    "name": "Pods"
  },
  {
    "cidrs": [
      "172.30.0.0/16"
    ],
    "name": "Services"
  },
  {
    "cidrs": [
      "10.0.0.0/16"
    ],
    "name": "Machines"
  }
]
```

You see from where the "Pods" and "Services" CIDRs are taken. The "Machines" subnet source is different, it comes from the initial k8s configuration.

When you open the Console plugin and configure columns to show the subnet labels, this is what you get:

![Subnet labels by default](./subnet-labels-default.png)

Every time `flowlogs-pipeline` has to process a network flow, it checks if the IP belongs to any of the defined subnet, and if so, it associates with the flow the related label.


Although the CIDR configuration mentioned above is the default when running on OpenShift, you can change it as much as you want. For instance, to add more machine networks, you can write in `FlowCollector`:

```yaml
spec:
  processor:
    subnetLabels:
      openShiftAutoDetect: true # (this is ignored when not running on OpenShift)
      customLabels:
      - cidrs: 
        - 10.0.0.0/16
        - 10.1.0.0/16
        - 10.2.0.0/16
        name: "Machines"
```

## How does that help for external traffic?

You can figure out what is the external traffic based on subnet labels, or the absence thereof. In this default configuration, all the cluster network entities are expected to be covered by these 3 subnets: Pods, Services and Machines. So all the rest is external.

In the Console plugin, you can for example filter for empty Destination Subnet Label, by setting an empty string in the filter:

![Filtering for empty destination subnet label](./filter-empty-subnet.png)

It gives you all the traffic to external workloads or services.

You can also create `FlowMetrics` resources dedicated to outside traffic. Thanksfully, we provide some examples that should work out of the box with the default subnet labels:

```bash
kubectl apply -n netobserv -f https://raw.githubusercontent.com/netobserv/network-observability-operator/refs/heads/main/config/samples/flowmetrics/cluster_external_egress_traffic.yaml
kubectl apply -n netobserv -f https://raw.githubusercontent.com/netobserv/network-observability-operator/refs/heads/main/config/samples/flowmetrics/cluster_external_ingress_traffic.yaml
```

(More examples available [here](https://github.com/netobserv/network-observability-operator/tree/main/config/samples/flowmetrics), including for external traffic latency)

These metrics leverage the absence of Subnet Labels in order to track external traffic. In Prometheus, you can query them with `promQL` such as:

```
topk(10, sum(rate(netobserv_cluster_external_egress_bytes_total{ SrcK8S_Namespace!="" }[2m])) by (SrcK8S_Namespace, SrcK8S_OwnerName))
```

![Prometheus/promql for external egress traffic](./external-promql.png)

Or in the OpenShift Console, navigate to Observe > Dashboards > NetObserv / Main:

![Dashboard external traffic](./dashboard-external-traffic.png)

## Going further: identifying the external workloads

All good so far, however this doesn't answer the question: where is this traffic flowing to (or from) ?

At this point, if we don't search into the per-flow details, we don't know. With the `FlowMetrics` API, we _could_ add the destination IPs as a metric label, however this is not recommended, because it results in a very high metrics cardinality, making your Prometheus index ballooning. If you try it, the `FlowMetrics` webhook will warn you about it. Let's try something different...

We'll take an example. The above picture shows that the OpenShift image registry has a regular ~500 KBps traffic rate to external IPs.

If we go back to the Console plugin and look at the image registry topology, aggregated per owner, here's what we get:

![Image registry topology unknown](./topology-unknown.png)

There are connections to several other cluster components, and this enigmatic "Unknown" element. Clicking on it will suggest two things that we can do:

![Image registry topology unknown with details](./topology-unknown-details.png)

1. Decrease scope aggregation
2. Configure subnet labels

Let's do 1, clicking on the "Resource" scope, on the left:

![Image registry topology per resource](./topology-resources.png)

Waw, that's plenty of different IPs! Ok, that helps a bit, but it's certainly not the best possible visualization.

A `whois` on any of these IPs tells us that it's Amazon S3 under the cover. So let's ask Amazon what CIDRs are used in our region:

```bash
curl https://ip-ranges.amazonaws.com/ip-ranges.json | jq -r '.prefixes[] | select(.region=="eu-west-3") | select(.service=="S3") | .ip_prefix'
16.12.20.0/24
52.95.156.0/24
3.5.204.0/22
52.95.154.0/23
16.12.18.0/23
3.5.224.0/22
13.36.84.48/28
13.36.84.64/28
```

We can inject them in our `subnetLabels` config:

```yaml
    subnetLabels:
      openShiftAutoDetect: true
      customLabels:
      - cidrs: 
        - 16.12.20.0/24
        - 52.95.156.0/24
        - 3.5.204.0/22
        - 52.95.154.0/23
        - 16.12.18.0/23
        - 3.5.224.0/22
        - 13.36.84.48/28
        - 13.36.84.64/28
        name: EXT-AWS_S3_eu-west-3
```

It is a good practice to use a common prefix for all labels on external traffic, such as "EXT-" here, in order to distinguish external and internal subnet labels.

You can go ahead and mark all the known external traffic in a similar way: databases, VMs, web services, etc.

{#admon title="Info"}
Granted, in the current release of NetObserv, going through every Subnet Labels configuration might be cumbersome. `FlowCollector` is a centralized API, typically managed by cluster admins, whereas knowing the various subnet dependencies might be more in the perimeter of application teams. We are currently working on a new feature that allows delegating that kind of configuration, so stay tuned!
{/}

Once we've created that label, we need to update our `FlowMetric` examples that filter on subnet label absence.

```bash
kubectl edit flowmetric flowmetric-cluster-external-egress-traffic -n netobserv
```

So instead of:

```yaml
  labels: [SrcK8S_HostName,SrcK8S_Namespace,SrcK8S_OwnerName,SrcK8S_OwnerType]
  filters:
  - field: DstSubnetLabel
    matchType: Absence
```

we would use now:

```yaml
  labels: [SrcK8S_HostName,SrcK8S_Namespace,SrcK8S_OwnerName,SrcK8S_OwnerType,DstSubnetLabel]
  filters:
  - field: DstSubnetLabel
    matchType: Absence
  - field: DstSubnetLabel
    matchType: MatchRegex
    value: "^EXT-.*"
```

so that traffic to our new label is considered as external. We can also add `DstSubnetLabel` to the list of labels in the generated metric, for a finer granularity of the destinations.

A similar change can be done for the ingress traffic.

{#admon title="Info"}
In `FlowMetrics`, when there are several filters for the same key, those filters are OR'ed, ie. the match is satisfied if one at least is satisfied. Filters on different keys are AND'ed.
{/}

With this setup, we are finally able to understand where the traffic is flowing to:

![Prometheus/promql for external egress traffic, labelled](./external-promql-labelled.png)
_Our destination label appears in the Prometheus metrics._

As well as in the topology view:

![Image registry topology labelled](./topology-labelled.png)
_Our destination label is visible as a topology element._

## Wrapping it up

We've seen:
- How NetObserv monitors all the traffic, internal and external.
- How we can use the subnet labels to mark both the internal and the external traffic.
- How to leverage it in metrics with the `FlowMetrics` API.
- And finally how to visualize that with a Prometheus console or with the NetObserv Console plugin.

As always, you can reach out to the development team on Slack (#netobserv-project on https://slack.cncf.io/) or via our [discussion pages](https://github.com/orgs/netobserv/discussions).
