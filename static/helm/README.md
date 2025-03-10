# NetObserv Operator

NetObserv Operator is a Kubernetes / OpenShift operator for network observability. It deploys a monitoring pipeline that consists in:
- an eBPF agent, that generates network flows from captured packets
- flowlogs-pipeline, a component that collects, enriches and exports these flows
- when used in OpenShift, a Console plugin for flows visualization with powerful filtering options, a topology representation and more

Flow data is then available in multiple ways, each optional:

- As Prometheus metrics
- As raw flow logs stored in Loki
- As raw flow logs exported to a collector
