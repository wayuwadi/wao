#!/usr/bin/env node
import { run } from '../src/cli.js'

run(process.argv.slice(2)).catch(error => {
  process.stderr.write(`wao: ${error.message}\n`)
  process.exitCode = 1
})
