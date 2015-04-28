package com.github.flowengine.model;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;

import com.github.flowengine.engine.AsyncTaskExecutor;
import com.github.flowengine.engine.TaskExecutor;
import com.github.flowengine.model.def.FlowTaskDef;
import com.github.flowengine.util.Listener;
import com.github.flowengine.util.Listenerable;
import com.github.rapid.common.util.ScriptEngineUtil;
/**
 * 流程任务实例
 * @author badqiu
 *
 */
public class FlowTask extends FlowTaskDef<FlowTask> implements Comparable<FlowTask>{

	private static Logger logger = LoggerFactory.getLogger(FlowTask.class);
	
	private String instanceId; //实例ID
	private String flowInstanceId; //任务执行批次ID,可以使用如( flow instanceId填充)
	
	private String status; //任务状态: 可运行,运行中,阻塞(睡眠,等待),停止
	private int execResult = Integer.MIN_VALUE; //执行结果: 0成功,非0为失败
	private boolean forceExec; //是否强制执行
	private int usedRetryTimes; //已经重试执行次数
	/**
     * 任务执行耗时       
     */ 	
	private long execCostTime;
    /**
     * 任务执行的开发时间       
     */ 	
	private java.util.Date execStartTime;
	/**
     * 任务执行日志       db_column: task_log 
     */ 	
	private java.lang.StringBuilder taskLog = new java.lang.StringBuilder();
	
	private Map context = new HashMap(); //保存上下文内容
	
	/**
	 * 最后执行的异常
	 */
	private Throwable exception;
	
	private transient Listenerable<FlowTask> listenerable = new Listenerable<FlowTask>();
	
	/**
	 * 未执行完成的父亲节点
	 */
	private Set<FlowTask> unFinishParents = new HashSet<FlowTask>();
	
	public FlowTask() {
	}
	
	public FlowTask(String taskCode) {
		this(null,taskCode);
	}
	
	public FlowTask(String flowCode, String taskCode) {
		super(flowCode,taskCode);
	}
	
	public FlowTask(String taskCode,String depends,Class<? extends TaskExecutor> scriptType) {
		this(null,taskCode);
		setDepends(depends);
		setScriptType(scriptType);
	}
	
	public FlowTask(String flowCode, String taskCode,String instanceId,String flowInstanceId) {
		super(flowCode, taskCode);
		this.instanceId = instanceId;
		this.flowInstanceId = flowInstanceId;
	}
	
	
	public String getInstanceId() {
		return instanceId;
	}

	public void setInstanceId(String instanceId) {
		this.instanceId = instanceId;
	}

	public String getFlowInstanceId() {
		return flowInstanceId;
	}

	public void setFlowInstanceId(String flowInstanceId) {
		this.flowInstanceId = flowInstanceId;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public int getExecResult() {
		return execResult;
	}

	public void setExecResult(int execResult) {
		this.execResult = execResult;
	}

	public long getExecCostTime() {
		return execCostTime;
	}

	public void setExecCostTime(long execCostTime) {
		this.execCostTime = execCostTime;
	}

	public java.util.Date getExecStartTime() {
		return execStartTime;
	}

	public void setExecStartTime(java.util.Date execStartTime) {
		this.execStartTime = execStartTime;
	}

	public java.lang.StringBuilder getTaskLog() {
		return taskLog;
	}

	public void setTaskLog(java.lang.StringBuilder taskLog) {
		this.taskLog = taskLog;
	}
	
	public void addTaskLog(String txt) {
		if(taskLog == null) {
			taskLog = new StringBuilder();
		}
		this.taskLog.append(txt);
	}

	public Throwable getException() {
		return exception;
	}

	public void setException(Throwable exception) {
		this.exception = exception;
	}

	public boolean isForceExec() {
		return forceExec;
	}

	public void setForceExec(boolean forceExec) {
		this.forceExec = forceExec;
	}

	public int getUsedRetryTimes() {
		return usedRetryTimes;
	}

	public void setUsedRetryTimes(int usedRetryTimes) {
		this.usedRetryTimes = usedRetryTimes;
	}

	public Map getContext() {
		return context;
	}

	public void setContext(Map context) {
		this.context = context;
	}

	public Listenerable<FlowTask> getListenerable() {
		return listenerable;
	}

	public void setListenerable(Listenerable<FlowTask> listenerable) {
		this.listenerable = listenerable;
	}
	
	public Set<FlowTask> getUnFinishParents() {
		return unFinishParents;
	}

	public void setUnFinishParents(Set<FlowTask> unFinishParents) {
		this.unFinishParents = unFinishParents;
	}
	
	public void addUnFinisheParent(FlowTask unFinisheParent) {
		if(!unFinishParents.contains(unFinisheParent)) {
			unFinishParents.add(unFinisheParent);
		}
	}

	public void exec(final FlowContext context,final boolean execParents,final boolean execChilds) {
		beforeExec(context);
		
		if(execParents) {
			execAll(context,execParents, execChilds,getParents(),true);
		}
		
		
		try {
			execSelf(context);
		} catch (Exception e) {
			throw new RuntimeException("error on exec,flowTask:"+this,e);
		} 
		
		if(execChilds) {
			execAll(context,execParents, execChilds,getChilds(),true);
		}
		
		afterExec(context);
	}

	protected void afterExec(FlowContext context2) {
	}

	protected void beforeExec(FlowContext context2) {
	}

	private synchronized void execSelf(final FlowContext context) throws InstantiationException,
			IllegalAccessException, ClassNotFoundException,
			InterruptedException, IOException {
		//判断所有父亲是否已完全执行
		if(CollectionUtils.isNotEmpty(getUnFinishParents())) {
			return;
		}
		if(!isEnabled()) {
			throw new RuntimeException("task no enabled,taskId:"+getTaskId());
		}
		
		Assert.hasText(getScriptType(),"scriptType must be not empty");
		
//		String flowCodeWithTaskCode = getFlowCode() + "/" + getTaskCode();
//		if(context.getVisitedTaskCodes().contains(flowCodeWithTaskCode)) {
//			return;
//		}
//		context.getVisitedTaskCodes().add(flowCodeWithTaskCode);
		
		TaskExecutor executor = lookupTaskExecutor(context);
		execStartTime = new Date();
		evalGroovy(context,getBeforeGroovy());
		
		
//		Retry.retry(getRetryTimes(), getRetryInterval(), getTimeout(), new Callable<Object>() {
//			@Override
//			public Object call() throws Exception {
//				
//				return null;
//			}
//		});
		while(true) {
			long start = System.currentTimeMillis();
			try {
				status = "RUNNING";
				logger.info("start execute task,id:"+getTaskId()+" usedRetryTimes:"+this.usedRetryTimes+" TaskExecutor:"+executor+" exception:"+exception);
				
				this.exception = null;
				
				if(getPreSleepTime() > 0) {
					Thread.sleep(getPreSleepTime());
				}
				
				notifyListeners();
				executor.exec(this, context);
				
				waitIfRunning(executor, context, this);
				
				if(executor instanceof AsyncTaskExecutor) {
					this.execResult = ((AsyncTaskExecutor)executor).getExitCode(this, context.getParams());
				}else {
					this.execResult = 0;
				}
				
				if(execResult != 0) {
					throw new RuntimeException("execResult not zero,execResult:"+this.execResult);
				}
				
				evalGroovy(context,getAfterGroovy());
				
				notifyListeners();
				break;
			}catch(Exception e) {
				logger.warn("exec "+getTaskId()+" error",e);
				this.exception = e;
				if(this.usedRetryTimes >= getRetryTimes()) {
					this.execResult = (this.execResult == 0) ? 1 : this.execResult;
					break;
				}
				this.usedRetryTimes = this.usedRetryTimes + 1;
				notifyListeners();
				if(getRetryInterval() > 0) {
					Thread.sleep(getRetryInterval());
				}
			}finally {
				this.execCostTime = System.currentTimeMillis() - start;
				if(getTimeout() > 0) {
					if(this.execCostTime > getTimeout() ) {
						break;
					}
				}
			}
		}
		
		if(this.execResult != 0) {
			evalGroovy(context,getErrorGroovy());
		}
		
		if(executor instanceof AsyncTaskExecutor) {
			addTaskLog( IOUtils.toString(((AsyncTaskExecutor)executor).getLog(this, context.getParams())) );
		}else {
			if(exception != null) {
				addTaskLog( ExceptionUtils.getFullStackTrace(exception) );
				logger.error("error execute on taskId:"+getTaskId(),exception);
			}
		}
		
		this.status = "END";
		notifyListeners();
		
		//执行成功,或者执行不成功但失败可忽略,在其孩子的未完成父亲集合中去掉当前任务
		if(this.execResult == 0 || (this.execResult != 0 && this.isIgnoreError())) {
			for(FlowTask flowTask : this.getChilds()) {
				flowTask.getUnFinishParents().remove(this);
			}
		}
		//否则整个流程标记为失败，并且其孩子节点将不会执行
		else {
			context.getFlow().setExecResult(1);
		}
		
	}

	private TaskExecutor lookupTaskExecutor(FlowContext context) throws InstantiationException,
			IllegalAccessException, ClassNotFoundException {
		Assert.hasText(getScriptType(),"scriptType must be not empty");
		TaskExecutor taskExecutor = context.getFlowEngine().getTaskExecutor(getScriptType());
		if(taskExecutor == null) {
			return (TaskExecutor)Class.forName(getScriptType()).newInstance();
		}
		return taskExecutor;
	}

	private void evalGroovy(final FlowContext context,String script) {
		if(StringUtils.isNotBlank(script)) {
			ScriptEngineUtil.eval("groovy", script, context.getParams());
		}
	}

	private void waitIfRunning(TaskExecutor executor,final FlowContext context,final FlowTask flowTask) throws InterruptedException {
		if(executor instanceof AsyncTaskExecutor) {
			while(((AsyncTaskExecutor)executor).isRunning(flowTask, context.getParams())) {
				Thread.sleep(1000 * 5);
			}
		}
	}
	
	public void notifyListeners() {
		if(listenerable != null) {
			listenerable.notifyListeners(this, null);
		}
	}

	public void addListener(Listener<FlowTask> t) {
		if(listenerable == null) {
			listenerable = new Listenerable<FlowTask>();
		}
		listenerable.addListener(t);
	}

	/**
	 * 通过计算得出的权重,如孩子越多,则权重越高,孩子自身的权重可以传递给父亲
	 * @return
	 */
	public int computePriority() {
		return getPriority();
	}
	
	@Override
	public int compareTo(FlowTask o) {
		return -new Integer(computePriority()).compareTo(o.computePriority());
	}

	public static void execAll(final FlowContext context, final boolean execParents,final boolean execChilds, Collection<FlowTask> tasks,boolean waitTasksExecEnd) {
		if(CollectionUtils.isEmpty(tasks)) {
			return;
		}
		
		Assert.notNull(context.getExecutorService(),"context.getExecutorService() must be not null");
		
		List<FlowTask> sortedTasks = new ArrayList<FlowTask>(tasks);
		Collections.sort(sortedTasks);
		
		final CountDownLatch dependsCountDownLatch = new CountDownLatch(tasks.size());
		for(final FlowTask depend : sortedTasks) {
			context.getExecutorService().execute(new Runnable() {
				@Override
				public void run() {
					try {
						depend.exec(context,execParents,execChilds);
					}catch(Exception e) {
						e.printStackTrace();
					}finally {
						dependsCountDownLatch.countDown();
					}
				}
			});
		}
		
		if(waitTasksExecEnd) {
			try {
				dependsCountDownLatch.await();
			} catch (InterruptedException e) {
				throw new RuntimeException("interrupt",e);
			}
		}
	}
	
	
}