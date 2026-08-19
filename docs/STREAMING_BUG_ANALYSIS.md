# 流式传输失败问题分析

> 创建时间：2026-08-17
> 问题现象：C 端收到完整信息但不是流式传输（没有 stream_delta，直接收到完整回复）
> 影响范围：开启工具模式（enable_tools=True）或使用不支持流式的 Provider 时

---

## 问题根因

### 核心问题：`tool_loop_agent` 吞掉了流式增量

AstrBot 的 `context.tool_loop_agent()` 内部支持流式（`stream=True`），但**没有把流式增量暴露给调用者**：

```python
# /d/project/AstrBot/astrbot/core/star/context.py

async def tool_loop_agent(self, ...):
    # ...
    agent_runner = ToolLoopAgentRunner()
    streaming = kwargs.get("stream", False)
    await agent_runner.reset(..., streaming=streaming, ...)

    async for _ in agent_runner.step_until_done(max_steps):
        pass  # ⚠️ 流式增量被消费了，但没有暴露出去！

    llm_resp = agent_runner.get_final_llm_resp()  # 只返回最终结果
    return llm_resp
```

`step_until_done` 是个 AsyncGenerator，会 yield 流式增量：
```python
# AgentResponse(type="streaming_delta", data=AgentResponseData(chain=...))
```

但 `tool_loop_agent` 直接 `pass` 掉了，只返回最终完整回复。

### fairy-voice 插件调用方式

```python
# /d/project/fairy-voice/main.py

if self._enable_tools and ToolSet is not None:
    resp = await self.context.tool_loop_agent(
        event=event,
        chat_provider_id=provider_id,
        contexts=contexts,
        tools=self._all_tools(),
        max_steps=self._tool_max_steps,
    )
    full = resp.completion_text
    if full:
        yield full  # ⚠️ 只 yield 一次完整回复，没有流式增量
```

**结果**：ws_server 收到一次性 yield → 只发 `stream_begin` + `stream_end`（中间没有 `stream_delta`）→ C 端看到"收到了信息但不是流式的"。

---

## 修复方案

### 方案：插件直接迭代 `step_until_done`（不使用 `tool_loop_agent`）

绕过 `tool_loop_agent`，直接创建 runner 并迭代流式增量：

```python
# main.py _handle_ask_stream

if self._enable_tools and ToolSet is not None:
    # 不使用 tool_loop_agent，直接创建 runner
    from astrbot.core.astr_agent_context import AgentContextWrapper, AstrAgentContext
    from astrbot.core.astr_agent_tool_exec import FunctionToolExecutor
    from astrbot.core.agent.runners.tool_loop_agent_runner import ToolLoopAgentRunner

    prov = await self.context.provider_manager.get_provider_by_id(provider_id)
    agent_runner = ToolLoopAgentRunner()
    tool_executor = FunctionToolExecutor()
    agent_context = AstrAgentContext(context=self, event=event)

    request = ProviderRequest(
        prompt=text,
        func_tool=self._all_tools(),
        contexts=[m.model_dump() if hasattr(m, 'model_dump') else m for m in contexts],
        system_prompt=session.summary,
    )

    await agent_runner.reset(
        provider=prov,
        request=request,
        run_context=AgentContextWrapper(context=agent_context),
        tool_executor=tool_executor,
        streaming=True,  # 启用流式
    )

    full = ""
    async for response in agent_runner.step_until_done(self._tool_max_steps):
        if response.type == "streaming_delta" and response.data.chain:
            # 提取增量文本
            delta_text = ""
            for seg in response.data.chain.chain:
                if hasattr(seg, 'text'):
                    delta_text += seg.text
            if delta_text:
                full += delta_text
                yield delta_text  # 逐增量 yield

    session.add_assistant(full)
```

**关键改动**：
1. 创建 `ToolLoopAgentRunner` 并传入 `streaming=True`
2. 迭代 `step_until_done`，提取 `streaming_delta` 类型的响应
3. 逐增量 yield，而不是一次性 yield 完整回复

**优点**：
- 不依赖 AstrBot 上游修改
- 工具模式也支持流式输出
- 保持 ws_server 和 C 端代码不变

**缺点**：
- 需要处理 `step_until_done` 的各种响应类型（streaming_delta、agent_stats 等）
- 代码比直接调用 `tool_loop_agent` 复杂

---

## 修复状态

✅ **已修复**（2026-08-17）

修复内容：`main.py` 的 `_handle_ask_stream` 工具模式路径改为直接迭代 `step_until_done`，提取 `streaming_delta` 增量逐段 yield。

关键代码变更：
```python
# 旧（一次性 yield 完整回复）
resp = await self.context.tool_loop_agent(...)
yield resp.completion_text

# 新（逐增量 yield）
async for response in agent_runner.step_until_done(self._tool_max_steps):
    if response.type == "streaming_delta" and response.data.chain:
        delta_text = extract_text(response.data.chain)
        if delta_text:
            yield delta_text
```

---

## 验证方法

修复后验证：

1. B 端开启工具模式（`enable_tools=True`）
2. C 端发送指令（如"今天天气怎么样"）
3. 观察 B 端日志：
   - 是否多次 yield delta（而不是一次性 yield 完整回复）
4. C 端日志：
   - 是否收到 stream_begin → 多个 stream_delta → stream_end
   - 对话页/悬浮卡是否增量显示（打字机效果）
5. 边界测试：触发工具调用的指令（如"帮我搜索xxx"），验证工具执行期间流式是否正常
