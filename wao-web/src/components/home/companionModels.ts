import * as THREE from 'three'

/** 独立程序化角色与毛绒材质，不加载外站模型。每次创建拥有自己的资源。 */
export function createCompanionModels(only?: number) {
  const black = new THREE.MeshStandardMaterial({ color: 0x15191a, roughness: 0.8 }),
    glass = new THREE.MeshStandardMaterial({ color: 0x11161a, roughness: 0.33 }),
    white = new THREE.MeshStandardMaterial({ color: 0xf5ffe3, roughness: 0.8 })
  let randomState = 41324
  function rand() {
    randomState = (Math.imul(randomState, 1664525) + 1013904223) >>> 0
    return randomState / 4294967296
  }
  function outline(kind: string, a: number) {
    if (kind === 'round') return new THREE.Vector2(Math.cos(a), Math.sin(a))
    if (kind === 'heart') {
      const t = a + Math.PI
      return new THREE.Vector2(
        (16 * Math.sin(t) ** 3) / 15,
        (13 * Math.cos(t) - 5 * Math.cos(2 * t) - 2 * Math.cos(3 * t) - Math.cos(4 * t)) / 16,
      )
    }
    if (kind === 'yellow') {
      const r = 1 - 0.2 * Math.sin(a * 3)
      return new THREE.Vector2(Math.cos(a) * r * 0.93, Math.sin(a) * r * 1.04)
    }
    if (kind === 'blue') {
      const r = 1 + 0.065 * Math.cos(a * 5 + 0.5) + 0.035 * Math.sin(a * 3)
      return new THREE.Vector2(Math.cos(a) * r, Math.sin(a) * r * 0.96)
    }
    return new THREE.Vector2(Math.cos(a) * 1.08, Math.sin(a) * 0.77)
  }
  function surface(kind: string, a: number, t: number) {
    const p = outline(kind, a),
      s = Math.sin(t)
    return new THREE.Vector3(p.x * s, p.y * s, Math.cos(t) * 0.66)
  }
  function furBody(kind: string, color: number) {
    const root = new THREE.Group(),
      geo = new THREE.BufferGeometry(),
      pos = [],
      indices = [],
      N = 100,
      M = 56
    for (let j = 0; j <= M; j++)
      for (let i = 0; i <= N; i++) {
        const v = surface(kind, (i / N) * Math.PI * 2, (j / M) * Math.PI)
        pos.push(v.x, v.y, v.z)
      }
    for (let j = 0; j < M; j++)
      for (let i = 0; i < N; i++) {
        const a = j * (N + 1) + i,
          b = a + N + 1
        indices.push(a, b, a + 1, b, b + 1, a + 1)
      }
    geo.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3))
    geo.setIndex(indices)
    geo.computeVertexNormals()
    root.add(
      new THREE.Mesh(
        geo,
        new THREE.MeshPhysicalMaterial({
          color,
          roughness: 0.98,
          sheen: 0.8,
          sheenColor: new THREE.Color(color),
          sheenRoughness: 0.85,
          side: THREE.DoubleSide,
        }),
      ),
    )
    const strandPositions = [],
      strandColors = [],
      base = new THREE.Color(color),
      light = new THREE.Vector3(-0.4, 0.8, 1).normalize()
    // 细短弯曲纤维：根部压暗、尖端透光；只在可见半球布毛，控制开销。
    for (let i = 0; i < (kind === 'round' ? 5500 : 30000); i++) {
      const a = rand() * Math.PI * 2,
        t = Math.acos(rand() * 1.1 - 0.1),
        p = surface(kind, a, t)
      const da = surface(kind, a + 0.002, t).sub(p),
        dt = surface(kind, a, t + 0.002).sub(p)
      const n = new THREE.Vector3().crossVectors(da, dt).normalize()
      if (n.dot(p) < 0) n.negate()
      const len = 0.014 + rand() * 0.027,
        bend = new THREE.Vector3(
          (rand() - 0.5) * 0.017,
          -0.007 - rand() * 0.016,
          (rand() - 0.5) * 0.014,
        )
      const mid = p.clone().addScaledVector(n, len * 0.55),
        tip = p.clone().addScaledVector(n, len).add(bend),
        shade = 0.44 + Math.max(0, n.dot(light)) * 0.46 + rand() * 0.17
      strandPositions.push(...p.toArray(), ...mid.toArray(), ...mid.toArray(), ...tip.toArray())
      for (const multiplier of [0.68, 0.95, 0.95, 1.14]) {
        const c = base.clone().multiplyScalar(shade * multiplier)
        strandColors.push(c.r, c.g, c.b)
      }
    }
    const f = new THREE.BufferGeometry()
    f.setAttribute('position', new THREE.Float32BufferAttribute(strandPositions, 3))
    f.setAttribute('color', new THREE.Float32BufferAttribute(strandColors, 3))
    root.add(
      new THREE.LineSegments(
        f,
        new THREE.LineBasicMaterial({ vertexColors: true, transparent: true, opacity: 0.72 }),
      ),
    )
    return root
  }
  function ball(
    parent: THREE.Object3D,
    material: THREE.Material,
    x: number,
    y: number,
    z: number,
    sx: number,
    sy: number,
    sz: number,
  ) {
    const m = new THREE.Mesh(new THREE.SphereGeometry(1, 28, 20), material)
    m.position.set(x, y, z)
    m.scale.set(sx, sy, sz)
    parent.add(m)
    return m
  }
  function line(
    parent: THREE.Object3D,
    points: [number, number, number][],
    r = 0.025,
    material = black,
  ) {
    const c = new THREE.CatmullRomCurve3(points.map((p) => new THREE.Vector3(p[0], p[1], p[2])))
    const mesh = new THREE.Mesh(new THREE.TubeGeometry(c, 20, r, 8, false), material)
    parent.add(mesh)
    return mesh
  }
  function ring(parent: THREE.Object3D, x: number, y: number, z: number, sx: number, sy: number) {
    const m = new THREE.Mesh(new THREE.TorusGeometry(0.34, 0.028, 10, 64), black)
    m.position.set(x, y, z)
    m.scale.set(sx, sy, 1)
    parent.add(m)
    return m
  }
  const colors = [0x00b5f7, 0xffca0d, 0xf42cdb, 0xb3ec22],
    kinds = ['blue', 'yellow', 'heart', 'frog']
  const pets = kinds.flatMap((kind, i) => {
    if (only !== undefined && i !== only) return []
    const group = new THREE.Group(),
      body = new THREE.Group()
    group.add(body)
    const shape = furBody(kind, colors[i]!)
    body.add(shape)
    const eyes: THREE.Object3D[] = [],
      pupils: THREE.Mesh[] = []
    if (i === 0) {
      for (const x of [-0.27, 0.27]) eyes.push(ball(body, glass, x, 0.18, 0.82, 0.078, 0.12, 0.055))
      const hat = new THREE.Group()
      hat.position.set(-0.2, 0.87, 0.06)
      hat.rotation.z = 0.22
      ball(hat, black, 0, 0, 0, 0.87, 0.23, 0.62)
      ball(hat, black, -0.18, 0.19, -0.05, 0.06, 0.065, 0.06)
      body.add(hat)
    }
    if (i === 1) {
      for (const x of [-0.33, 0.33]) {
        ring(body, x, 0.19, 0.69, 0.87, 1.25)
        const eye = new THREE.Group()
        eye.position.set(x, 0.19, 0.7)
        line(
          eye,
          [
            [-0.13, 0.015, 0],
            [0, -0.035, 0.01],
            [0.13, 0.015, 0],
          ],
          0.025,
        )
        body.add(eye)
        eyes.push(eye)
      }
      line(
        body,
        [
          [-0.055, 0.24, 0.74],
          [0, 0.27, 0.76],
          [0.055, 0.24, 0.74],
        ],
        0.022,
      )
      line(
        body,
        [
          [-0.66, 0.22, 0.6],
          [-0.79, 0.2, 0.28],
        ],
        0.024,
      )
      line(
        body,
        [
          [0.66, 0.22, 0.6],
          [0.78, 0.2, 0.28],
        ],
        0.024,
      )
    }
    if (i === 2) {
      for (const x of [-0.32, 0.32]) ball(body, black, x, -0.02, 0.72, 0.265, 0.26, 0.045)
      line(
        body,
        [
          [-0.07, 0.05, 0.76],
          [0, 0.13, 0.8],
          [0.07, 0.05, 0.76],
        ],
        0.028,
      )
      line(
        body,
        [
          [-0.15, -0.42, 0.62],
          [0, -0.49, 0.65],
          [0.15, -0.42, 0.62],
        ],
        0.02,
      )
    }
    if (i === 3) {
      for (const x of [-0.5, 0.5]) {
        const bump = furBody('round', colors[i]!)
        bump.position.set(x, 0.63, 0)
        bump.scale.set(0.36, 0.4, 0.62)
        body.add(bump)
        const eye = ball(body, white, x, 0.62, 0.52, 0.245, 0.255, 0.1)
        eyes.push(eye)
        pupils.push(ball(body, glass, x, 0.63, 0.615, 0.164, 0.17, 0.065))
      }
      line(
        body,
        [
          [-0.27, -0.12, 0.66],
          [0, -0.23, 0.7],
          [0.27, -0.12, 0.66],
        ],
        0.017,
      )
      const bow = new THREE.Group()
      bow.position.set(0, -0.65, 0.55)
      ball(bow, black, -0.18, 0, 0, 0.23, 0.13, 0.08).rotation.z = -0.4
      ball(bow, black, 0.18, 0, 0, 0.23, 0.13, 0.08).rotation.z = 0.4
      ball(bow, black, 0, 0, 0.04, 0.075, 0.085, 0.06)
      body.add(bow)
    }
    const shadowMaterial = new THREE.MeshBasicMaterial({
      color: 0x5c4569,
      transparent: true,
      opacity: 0.035,
      depthWrite: false,
    })
    const shadow = new THREE.Mesh(new THREE.CircleGeometry(1, 50), shadowMaterial)
    shadow.scale.set(1, 0.13, 1)
    shadow.position.set(0, -1.1, -0.8)
    group.add(shadow)
    return {
      group,
      body,
      eyes,
      pupils,
      shadow,
      i,
      x: 0,
      y: -1000,
      vx: 0,
      vy: 0,
      homeX: 0,
      homeY: 0,
      size: 80,
      phase: rand() * 6,
      blinkAt: 2 + rand() * 4,
      blink: 0,
      action: 0,
      drag: false,
      hover: false,
      tilt: 0,
    }
  })

  return pets
}
export type CompanionModel = ReturnType<typeof createCompanionModels>[number]
