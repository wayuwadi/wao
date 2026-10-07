import assert from 'node:assert/strict'
import { mkdir,writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { run } from '../src/cli.js'
let raw='';for await(const chunk of process.stdin)raw+=chunk
const config=JSON.parse(raw);raw=''
assert.equal(new URL(config.url).origin,'http://127.0.0.1:18088')
await mkdir(config.evidenceDir,{recursive:true})
async function cli(args){let out='';await run([...args,'--output','json','--url',config.url],{tokenProvider:async()=>config.token,stdin:Readable.from([]),stdout:{write(v){out+=v}},stderr:{write(){}},receiptDirectory:join(config.evidenceDir,'operations')});return JSON.parse(out)}
const created=await cli(['pets','upload','--file',config.fixture,'--source','CLI验收专用，基于主人素材；验收后保持私人','--license','ALL-RIGHTS-RESERVED'])
assert.equal(created.listed,false)
assert.ok((await cli(['pets','list'])).some(p=>p.id===created.id))
let shared
try {
 shared=await cli(['pets','share',created.id,'--yes']);assert.equal(shared.verification.ok,true)
 assert.equal((await fetch(new URL('/api/public/pets/'+created.id,new URL(config.url).origin))).status,200)
} finally {await cli(['pets','unshare',created.id])}
assert.equal((await fetch(new URL('/api/public/pets/'+created.id,new URL(config.url).origin))).status,404)
assert.equal((await cli(['pets','list'])).find(p=>p.id===created.id).listed,false)
await assert.rejects(cli(['pets','upload','--file',config.fixture,'--source','CLI验收专用，基于主人素材；验收后保持私人','--license','ALL-RIGHTS-RESERVED']),/回执已存在/)
await writeFile(join(config.evidenceDir,'summary.json'),JSON.stringify({kind:'real-existing-public-runtime-mysql-cli',petId:created.id,privateUpload:true,publicHashVerified:shared.verification.ok,unshared:true,unknownRetryProtected:true,retainedMarkedPrivateFixture:true},null,2))
console.log('Pet CLI upload, list, share, public hash, unshare, receipt guard passed.')
