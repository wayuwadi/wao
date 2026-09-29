#!/usr/bin/env python3
"""Add a fully installed release without changing operator routing switches."""
import json
import os
from pathlib import Path
import re
import sys
import tempfile


def publish(path, release):
    if not re.fullmatch(r'[0-9a-f]{40}', release):
        raise ValueError('Expected full release SHA')
    path = Path(path)
    default = dict(schema=1, enabled=False, staticEnabled=False, forceCloud=True,
                   allowedReleases=[], nodes=[
                       dict(origin='https://linux2.wayuwa.cn:9443', enabled=False)])
    data = json.loads(path.read_text()) if path.exists() else default
    if data.get('schema') != 1 or any(type(data.get(k)) is not bool for k in ('enabled', 'staticEnabled', 'forceCloud')):
        raise ValueError('Invalid routing switches; refusing to reset operator configuration')
    previous = data.get('allowedReleases')
    if not isinstance(previous, list) or any(not isinstance(v, str) or not re.fullmatch(r'[0-9a-f]{40}', v) for v in previous):
        raise ValueError('Invalid release allowlist')
    nodes = data.get('nodes', default['nodes'])
    if not isinstance(nodes, list) or any(not isinstance(node, dict) or not isinstance(node.get('origin'), str)
                                          for node in nodes):
        raise ValueError('Invalid edge node list')
    data['nodes'] = [node for node in nodes
                     if node['origin'] != 'https://linux1.wayuwa.cn:9443']
    if not any(node['origin'] == 'https://linux2.wayuwa.cn:9443' for node in data['nodes']):
        data['nodes'].append(dict(origin='https://linux2.wayuwa.cn:9443', enabled=False))
    data['allowedReleases'] = list(dict.fromkeys([release, *previous]))[:4]
    path.parent.mkdir(parents=True, exist_ok=True)
    path.parent.chmod(0o755)
    descriptor, temp = tempfile.mkstemp(prefix='.routing-', dir=path.parent)
    try:
        with os.fdopen(descriptor, 'w') as output:
            json.dump(data, output, ensure_ascii=False)
            output.flush()
            os.fsync(output.fileno())
        os.chmod(temp, 0o644)
        os.replace(temp, path)
    finally:
        if os.path.exists(temp): os.unlink(temp)


if __name__ == '__main__':
    publish(sys.argv[1], sys.argv[2])
