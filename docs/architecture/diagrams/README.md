# Standalone architecture diagrams

This directory contains presentation-friendly renderings that complement the
canonical Mermaid source in the numbered Markdown documents.

- [system-overview.svg](system-overview.svg) is a large, plain-language map for
  newcomers and people outside the project. It organizes the system into four
  labeled planes (control, data, persistence, observation), shows the actors on
  the left, and annotates each component with the responsibility it serves plus
  the implementation module or protocol behind it (for example `registry-cardano`
  on the Topic Registry, or the SecureCyclon / Vicinity / Dissemination / Netty
  peer stack inside a Pub/Sub node). A color legend explains every arrow style.

The SVG is checked in because it can be opened directly in a browser, embedded
in slides, printed at large sizes, or exported to PNG without losing resolution.
When behavior changes, update it together with D1.
