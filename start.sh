#!/bin/bash

echo "========================================"
echo "   WenShape 一键启动"
echo "========================================"
echo ""

# 检查 Python
if ! command -v python3 &> /dev/null; then
    echo "[错误] 未检测到 Python，请先安装 Python 3.10+"
    echo "下载地址: https://www.python.org/downloads/"
    echo ""
    exit 1
fi

# 检查 Node.js
if ! command -v node &> /dev/null; then
    echo "[错误] 未检测到 Node.js，请先安装 Node.js 18+"
    echo "下载地址: https://nodejs.org/"
    echo ""
    exit 1
fi

# 获取脚本所在目录
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

PORTS="$(python3 - <<'PY'
import os, socket

def pick(start: int) -> int:
    start = int(start or 0)
    for p in range(max(start, 1), max(start, 1) + 30):
        try:
            s = socket.socket()
            s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            s.bind(("127.0.0.1", p))
            s.close()
            return p
        except OSError:
            pass
    return start or 0

bp = os.environ.get("WENSHAPE_BACKEND_PORT") or os.environ.get("PORT") or "8000"
fp = os.environ.get("WENSHAPE_FRONTEND_PORT") or os.environ.get("VITE_DEV_PORT") or "3000"
print(f"{pick(int(bp))},{pick(int(fp))}")
PY
)"
WENSHAPE_BACKEND_PORT="${PORTS%,*}"
WENSHAPE_FRONTEND_PORT="${PORTS#*,}"
export WENSHAPE_BACKEND_PORT WENSHAPE_FRONTEND_PORT

echo "[1/3] 启动后端服务..."
if osascript -e "tell app \"Terminal\" to do script \"cd '$SCRIPT_DIR/backend' && PORT='$WENSHAPE_BACKEND_PORT' WENSHAPE_BACKEND_PORT='$WENSHAPE_BACKEND_PORT' WENSHAPE_AUTO_PORT=1 ./run.sh\"" 2>/dev/null; then
    :
elif gnome-terminal -- bash -c "cd '$SCRIPT_DIR/backend' && PORT='$WENSHAPE_BACKEND_PORT' WENSHAPE_BACKEND_PORT='$WENSHAPE_BACKEND_PORT' WENSHAPE_AUTO_PORT=1 ./run.sh; exec bash" 2>/dev/null; then
    :
elif xterm -e "cd '$SCRIPT_DIR/backend' && PORT='$WENSHAPE_BACKEND_PORT' WENSHAPE_BACKEND_PORT='$WENSHAPE_BACKEND_PORT' WENSHAPE_AUTO_PORT=1 ./run.sh" 2>/dev/null & then
    :
else
    echo "  [提示] 无法打开独立终端窗口，后端服务将在后台运行，日志输出至 backend/backend.log"
    cd "$SCRIPT_DIR/backend" && PORT="$WENSHAPE_BACKEND_PORT" WENSHAPE_BACKEND_PORT="$WENSHAPE_BACKEND_PORT" WENSHAPE_AUTO_PORT=1 nohup ./run.sh > backend.log 2>&1 &
fi

sleep 3

echo "[2/3] 启动前端服务..."
if osascript -e "tell app \"Terminal\" to do script \"cd '$SCRIPT_DIR/frontend' && VITE_DEV_PORT='$WENSHAPE_FRONTEND_PORT' WENSHAPE_FRONTEND_PORT='$WENSHAPE_FRONTEND_PORT' VITE_BACKEND_PORT='$WENSHAPE_BACKEND_PORT' WENSHAPE_BACKEND_PORT='$WENSHAPE_BACKEND_PORT' VITE_BACKEND_URL='http://localhost:$WENSHAPE_BACKEND_PORT' ./run.sh\"" 2>/dev/null; then
    :
elif gnome-terminal -- bash -c "cd '$SCRIPT_DIR/frontend' && VITE_DEV_PORT='$WENSHAPE_FRONTEND_PORT' WENSHAPE_FRONTEND_PORT='$WENSHAPE_FRONTEND_PORT' VITE_BACKEND_PORT='$WENSHAPE_BACKEND_PORT' WENSHAPE_BACKEND_PORT='$WENSHAPE_BACKEND_PORT' VITE_BACKEND_URL='http://localhost:$WENSHAPE_BACKEND_PORT' ./run.sh; exec bash" 2>/dev/null; then
    :
elif xterm -e "cd '$SCRIPT_DIR/frontend' && VITE_DEV_PORT='$WENSHAPE_FRONTEND_PORT' WENSHAPE_FRONTEND_PORT='$WENSHAPE_FRONTEND_PORT' VITE_BACKEND_PORT='$WENSHAPE_BACKEND_PORT' WENSHAPE_BACKEND_PORT='$WENSHAPE_BACKEND_PORT' VITE_BACKEND_URL='http://localhost:$WENSHAPE_BACKEND_PORT' ./run.sh" 2>/dev/null & then
    :
else
    echo "  [提示] 无法打开独立终端窗口，前端服务将在后台运行，日志输出至 frontend/frontend.log"
    cd "$SCRIPT_DIR/frontend" && VITE_DEV_PORT="$WENSHAPE_FRONTEND_PORT" WENSHAPE_FRONTEND_PORT="$WENSHAPE_FRONTEND_PORT" VITE_BACKEND_PORT="$WENSHAPE_BACKEND_PORT" WENSHAPE_BACKEND_PORT="$WENSHAPE_BACKEND_PORT" VITE_BACKEND_URL="http://localhost:$WENSHAPE_BACKEND_PORT" nohup ./run.sh > frontend.log 2>&1 &
fi

wait_for_service() {
    local name=$1
    local url=$2
    local timeout=${3:-180}
    echo "  正在查验 $name 状态 (最多等待 ${timeout}s)..."
    local start_time=$(date +%s)
    while true; do
        if curl -sSf "$url" > /dev/null 2>&1; then
            echo "  [OK] $name 已就绪！"
            return 0
        fi
        
        local current_time=$(date +%s)
        if (( current_time - start_time > timeout )); then
            echo "  [超时] $name 无法在 ${timeout} 秒内连接，请检查服务日志。"
            return 1
        fi
        sleep 2
    done
}

echo ""
echo "检查服务健康状态 (由于首次需要安装依赖，可能需要数分钟)..."
wait_for_service "后端 API" "http://localhost:$WENSHAPE_BACKEND_PORT/health" 300
wait_for_service "前端页面" "http://localhost:$WENSHAPE_FRONTEND_PORT" 120

echo ""
echo "[3/3] 服务启动完成！"
echo ""
echo "========================================"
echo " 访问地址:"
echo "----------------------------------------"
echo " 前端界面:   http://localhost:$WENSHAPE_FRONTEND_PORT"
echo " 后端 API:   http://localhost:$WENSHAPE_BACKEND_PORT"
echo " API 文档:   http://localhost:$WENSHAPE_BACKEND_PORT/docs"
echo "========================================"
echo ""
echo "提示: 前后端服务已在独立终端或后台启动"
echo "      请查看对应端目录下的日志 (.log) 获取运行状态"
echo "      可执行 killall node 和 pkill -f 'app.main' 停止后台服务"
echo ""

echo "尝试在浏览器中打开前端界面..."
if command -v open >/dev/null 2>&1; then
    open "http://localhost:$WENSHAPE_FRONTEND_PORT"
elif command -v xdg-open >/dev/null 2>&1; then
    xdg-open "http://localhost:$WENSHAPE_FRONTEND_PORT"
fi
