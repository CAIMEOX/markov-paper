#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 <paper-server.jar> <minecraft-version>" >&2
  exit 2
fi

paper_jar=$1
minecraft_version=$2

if [[ ! -f $paper_jar ]]; then
  echo "Paper server JAR does not exist: $paper_jar" >&2
  exit 2
fi

unzip -Z1 "$paper_jar" \
  | LC_ALL=C sort \
  | awk -v version="$minecraft_version" '
      BEGIN {
        print "# Generated from Paper " version " vanilla server resources."
        print "# Includes every Vanilla NBT Structure Template, including the authored-air sentinel."
      }
      /^data\/minecraft\/structure\/.+\.nbt$/ {
        key = $0
        sub(/^data\/minecraft\/structure\//, "", key)
        sub(/\.nbt$/, "", key)
        count = split(key, segment, "/")
        family = segment[1]

        dimensions = "dimension:overworld"
        if (family == "empty") dimensions = "dimension:any"
        if (family == "end_city") dimensions = "dimension:end"
        if (family == "bastion" || family == "nether_fossils") dimensions = "dimension:nether"
        if (family == "ruined_portal") dimensions = "dimension:overworld,dimension:nether"

        printf "minecraft:%s|family:%s,%s", key, family, dimensions
        delete seen
        for (i = 2; i < count; i++) {
          path_tag = "path:" segment[i]
          if (!(path_tag in seen)) {
            printf ",%s", path_tag
            seen[path_tag] = 1
          }
        }
        print ""
      }
    '
