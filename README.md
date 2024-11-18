# netobserv.io

This repository contains the code for the [netobserv.io](https://netobserv.io) web site / blog.

## How it works

It uses the static site generator [Quarkus Roq](https://pages.quarkiverse.io/quarkus-roq/) to generate a static HTML site based on markdown pages.

Most of the time, writing content simply requires you to drop a new markdown file in [content/posts](./content/posts/), and assets (such as images) in [static/assets](./static/assets/). A few meta-data is also required at the top of the markdown file, such as:

```
---
layout: :theme/post
title: "Your post title"
description: "The post description"
tags: Insert,Some,Tags,Here
authors: [your-name]
---

```

Then, open a pull-request and the post will be reviewed by maintainers.

First-time contributors should also provide some info about themselves in [data/authors.yml](./data/authors.yml).

## Testing locally

To test locally, the simplest way is to use the [quarkus CLI](https://quarkus.io/guides/cli-tooling).

After you cloned this repository, from its root, run:

```bash
quarkus dev
```

And then open http://localhost:8080/

## Deploying on netobserv.io

Deploying on netobserv.io is fully automated after pull-requests are merged. This is all handled via [a github action](./.github/workflows/static.yml) that runs quarkus to generate the static HTML artifact, which is deployed through GitHub pages.

## Contributions

Like all the other NetObserv components, this blog is open-source and we are welcoming external contributions. Feel free to open pull-requests!
