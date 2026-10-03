#!/bin/sh
# Main and games have separate outputs. Only affected games are rebuilt.
set -eu
: "${WAO_RUN_TEMP:?}"
mkdir -p "$WAO_RUN_TEMP"
full_web=''
[ "${WAO_WEB_ONLY:-false}" = true ] || full_web=--full-web
python3 wao-game/tools/change-plan.py "${WAO_CHANGE_BASE:-HEAD^}" "${WAO_GIT_SHA:-HEAD}" $full_web >"$WAO_RUN_TEMP/game-plan.json"
# Host imports save/protocol types from wao-game; dependency installation does not build games.
sh deploy/jenkins/ci-game-build.sh install
for game in $(python3 -c 'import json,sys; print(" ".join(json.load(open(sys.argv[1]))["testGames"]))' "$WAO_RUN_TEMP/game-plan.json"); do
  sh deploy/jenkins/ci-game-build.sh "test:$game"
done
if python3 -c 'import json,sys; sys.exit(not json.load(open(sys.argv[1]))["buildWeb"])' "$WAO_RUN_TEMP/game-plan.json"; then
  sh deploy/jenkins/ci-frontend-build.sh wao-web false true
else
  echo '主站未变化，复用现有主站静态制品。'
fi
for game in $(python3 -c 'import json,sys; print(" ".join(json.load(open(sys.argv[1]))["buildGames"]))' "$WAO_RUN_TEMP/game-plan.json"); do
  sh deploy/jenkins/ci-game-build.sh "$game"
  python3 wao-game/tools/game-release.py pack "wao-game/dist/$game" "$WAO_RUN_TEMP/game-$game.tgz" "$game" "$WAO_GIT_SHA"
done
