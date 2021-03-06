/**
 * The MIT License (MIT)
 * 
 * Copyright (c) 2016 Thomas Cashman
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.mini2Dx.miniscript.lua;

import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LoadState;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.compiler.LuaC;
import org.luaj.vm2.lib.Bit32Lib;
import org.luaj.vm2.lib.DebugLib;
import org.luaj.vm2.lib.PackageLib;
import org.luaj.vm2.lib.StringLib;
import org.luaj.vm2.lib.TableLib;
import org.luaj.vm2.lib.jse.JseBaseLib;
import org.luaj.vm2.lib.jse.JseMathLib;
import org.luaj.vm2.lib.jse.JsePlatform;
import org.mini2Dx.miniscript.core.GameScriptingEngine;
import org.mini2Dx.miniscript.core.PerThreadGameScript;
import org.mini2Dx.miniscript.core.ScriptBindings;
import org.mini2Dx.miniscript.core.ScriptExecutionTask;
import org.mini2Dx.miniscript.core.ScriptExecutor;
import org.mini2Dx.miniscript.core.ScriptExecutorPool;
import org.mini2Dx.miniscript.core.ScriptInvocationListener;
import org.mini2Dx.miniscript.core.exception.InsufficientCompilersException;
import org.mini2Dx.miniscript.core.exception.ScriptExecutorUnavailableException;

/**
 * An implementation of {@link ScriptExecutorPool} for Lua scripts
 */
public class LuaScriptExecutorPool implements ScriptExecutorPool<LuaValue> {
	private final Map<Long, Globals> threadCompilers = new ConcurrentHashMap<Long, Globals>();
	private final Map<Integer, PerThreadGameScript<LuaValue>> scripts = new ConcurrentHashMap<Integer, PerThreadGameScript<LuaValue>>();
	private final BlockingQueue<ScriptExecutor<LuaValue>> executors;
	private final GameScriptingEngine gameScriptingEngine;
	private final boolean sandboxed;
	
	private Globals sandboxedGlobals;

	public LuaScriptExecutorPool(GameScriptingEngine gameScriptingEngine, int poolSize, boolean sandboxed) {
		this.gameScriptingEngine = gameScriptingEngine;
		this.sandboxed = sandboxed;
		
		executors = new ArrayBlockingQueue<ScriptExecutor<LuaValue>>(poolSize);

		for (int i = 0; i < poolSize; i++) {
			executors.offer(new LuaScriptExecutor(this));
		}
		
		if(sandboxed) {
			//Create sandboxed compiler
			sandboxedGlobals = new Globals();
			sandboxedGlobals.load(new JseBaseLib());
			sandboxedGlobals.load(new PackageLib());
			sandboxedGlobals.load(new StringLib());

			// To load scripts, we occasionally need a math library in addition to compiler support.
			// To limit scripts using the debug library, they must be closures, so we only install LuaC.
			sandboxedGlobals.load(new JseMathLib());
			LoadState.install(sandboxedGlobals);
			LuaC.install(sandboxedGlobals);
		}
	}

	@Override
	public int preCompileScript(String scriptContent) throws InsufficientCompilersException {
		PerThreadGameScript<LuaValue> script = new PerThreadGameScript<LuaValue>(scriptContent);
		scripts.put(script.getId(), script);
		return script.getId();
	}

	@Override
	public ScriptExecutionTask<?> execute(int scriptId, ScriptBindings scriptBindings,
			ScriptInvocationListener invocationListener) {
		ScriptExecutor<LuaValue> executor = allocateExecutor();
		if (executor == null) {
			throw new ScriptExecutorUnavailableException(scriptId);
		}
		return new ScriptExecutionTask<LuaValue>(gameScriptingEngine, executor, scripts.get(scriptId),
				scriptBindings, invocationListener);
	}

	@Override
	public void release(ScriptExecutor<LuaValue> executor) {
		try {
			executors.put(executor);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	@Override
	public GameScriptingEngine getGameScriptingEngine() {
		return gameScriptingEngine;
	}
	
	private ScriptExecutor<LuaValue> allocateExecutor() {
		try {
			return executors.take();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		return null;
	}
	
	public Globals getLocalGlobals() {
		long threadId = Thread.currentThread().getId();
		if (!threadCompilers.containsKey(threadId)) {
			if(sandboxed) {
				threadCompilers.put(threadId, createSandboxedGlobals());
			} else {
				threadCompilers.put(threadId, JsePlatform.standardGlobals());
			}
		}
		return threadCompilers.get(threadId);
	}
	
	public LuaValue compileWithGlobals(Globals globals, String script) {
		if(sandboxed) {
			return sandboxedGlobals.load(script, "main", globals);
		} else {
			return globals.load(script);
		}
	}
	
	private Globals createSandboxedGlobals() {
		Globals result = new Globals();
		result.load(new JseBaseLib());
		result.load(new PackageLib());
		result.load(new Bit32Lib());
		result.load(new TableLib());
		result.load(new StringLib());
		result.load(new JseMathLib());
		result.load(new DebugLib());
		result.set("debug", LuaValue.NIL);
		return result;
	}
}
