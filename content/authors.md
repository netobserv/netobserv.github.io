---
title: Authors
description: |
  Authors on this blog.
layout: :theme/page
---

# Authors on this blog

<div class="authors">
  <!-- authors.yml is in the data/ -->
  {#for id in cdi:authors.fields}
    {#let author=cdi:authors.get(id)}
    <!-- the author-card tag is defined in the default Roq theme -->
    {#author-card name=author.name avatar=author.avatar nickname=author.nickname profile=author.profile}
      {#if author.bio}
        {author.bio}
      {/if}
    {/author-card}
  {/for}
</div>
