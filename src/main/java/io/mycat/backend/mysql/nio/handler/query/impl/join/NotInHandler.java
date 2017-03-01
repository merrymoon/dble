package io.mycat.backend.mysql.nio.handler.query.impl.join;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.log4j.Logger;

import io.mycat.MycatServer;
import io.mycat.backend.BackendConnection;
import io.mycat.backend.mysql.nio.MySQLConnection;
import io.mycat.backend.mysql.nio.handler.query.OwnThreadDMLHandler;
import io.mycat.backend.mysql.nio.handler.util.HandlerTool;
import io.mycat.backend.mysql.nio.handler.util.RowDataComparator;
import io.mycat.backend.mysql.nio.handler.util.TwoTableComparator;
import io.mycat.backend.mysql.store.LocalResult;
import io.mycat.backend.mysql.store.UnSortedLocalResult;
import io.mycat.buffer.BufferPool;
import io.mycat.net.mysql.FieldPacket;
import io.mycat.net.mysql.RowDataPacket;
import io.mycat.plan.Order;
import io.mycat.server.NonBlockingSession;
import io.mycat.util.FairLinkedBlockingDeque;

public class NotInHandler extends OwnThreadDMLHandler {
	private static final Logger logger = Logger.getLogger(NotInHandler.class);

	private FairLinkedBlockingDeque<LocalResult> leftQueue;
	private FairLinkedBlockingDeque<LocalResult> rightQueue;
	private List<Order> leftOrders;
	private List<Order> rightOrders;
	private List<FieldPacket> leftFieldPackets;
	private List<FieldPacket> rightFieldPackets;
	private BufferPool pool;
	private RowDataComparator leftCmptor;
	private RowDataComparator rightCmptor;
	private AtomicBoolean fieldSent = new AtomicBoolean(false);
	private int queueSize;
	private String charset = "UTF-8";

	public NotInHandler(long id, NonBlockingSession session, List<Order> leftOrder, List<Order> rightOrder) {
		super(id, session);
		this.leftOrders = leftOrder;
		this.rightOrders = rightOrder;
		this.queueSize = MycatServer.getInstance().getConfig().getSystem().getJoinQueueSize();
		this.leftQueue = new FairLinkedBlockingDeque<LocalResult>(queueSize);
		this.rightQueue = new FairLinkedBlockingDeque<LocalResult>(queueSize);
		this.leftFieldPackets = new ArrayList<FieldPacket>();
		this.rightFieldPackets = new ArrayList<FieldPacket>();
	}

	@Override
	public HandlerType type() {
		return HandlerType.JOIN;
	}

	@Override
	public void fieldEofResponse(byte[] headernull, List<byte[]> fieldsnull, final List<FieldPacket> fieldPackets,
			byte[] eofnull, boolean isLeft, final BackendConnection conn) {
		if (this.pool == null)
			this.pool = MycatServer.getInstance().getBufferPool();

		if (isLeft) {
			// logger.debug("field eof left");
			leftFieldPackets = fieldPackets;
			leftCmptor = new RowDataComparator(leftFieldPackets, leftOrders, this.isAllPushDown(), this.type(),
					conn.getCharset());
		} else {
			// logger.debug("field eof right");
			rightFieldPackets = fieldPackets;
			rightCmptor = new RowDataComparator(rightFieldPackets, rightOrders, this.isAllPushDown(), this.type(),
					conn.getCharset());
		}
		if (!fieldSent.compareAndSet(false, true)) {
			this.charset = conn.getCharset();
			nextHandler.fieldEofResponse(null, null, leftFieldPackets, null, this.isLeft, conn);
			// logger.debug("all ready");
			startOwnThread(conn);
		}
	}

	@Override
	public boolean rowResponse(byte[] rownull, RowDataPacket rowPacket, boolean isLeft, BackendConnection conn) {
		logger.debug("rowresponse");
		if (terminate.get()) {
			return true;
		}
		try {
			if (isLeft) {
				addRowToDeque(rowPacket, leftFieldPackets.size(), leftQueue, leftCmptor);
			} else {
				addRowToDeque(rowPacket, rightFieldPackets.size(), rightQueue, rightCmptor);
			}
		} catch (InterruptedException e) {
			logger.warn("not in row exception", e);
			return true;
		}
		return false;
	}

	@Override
	public void rowEofResponse(byte[] data, boolean isLeft, BackendConnection conn) {
		logger.info("roweof");
		if (terminate.get()) {
			return;
		}
		RowDataPacket eofRow = new RowDataPacket(0);
		try {
			if (isLeft) {
				// logger.debug("row eof left");
				addRowToDeque(eofRow, leftFieldPackets.size(), leftQueue, leftCmptor);
			} else {
				// logger.debug("row eof right");
				addRowToDeque(eofRow, rightFieldPackets.size(), rightQueue, rightCmptor);
			}
		} catch (Exception e) {
			logger.warn("not in rowEof exception", e);
		}
	}

	@Override
	protected void ownThreadJob(Object... objects) {
		MySQLConnection conn = (MySQLConnection) objects[0];
		LocalResult leftLocal = null, rightLocal = null;
		try {
			Comparator<RowDataPacket> notInCmptor = new TwoTableComparator(leftFieldPackets, rightFieldPackets,
					leftOrders, rightOrders, this.isAllPushDown(), this.type(), conn.getCharset());

			leftLocal = takeFirst(leftQueue);
			rightLocal = takeFirst(rightQueue);
			while (true) {
				RowDataPacket leftRow = leftLocal.getLastRow();
				RowDataPacket rightRow = rightLocal.getLastRow();
				if (leftRow.fieldCount == 0) {
					break;
				}
				if (rightRow.fieldCount == 0) {
					sendLeft(leftLocal, conn);
					leftLocal.close();
					leftLocal = takeFirst(leftQueue);
					continue;
				}
				int rs = notInCmptor.compare(leftRow, rightRow);
				if (rs < 0) {
					sendLeft(leftLocal, conn);
					leftLocal.close();
					leftLocal = takeFirst(leftQueue);
					continue;
				} else if (rs > 0) {
					rightLocal.close();
					rightLocal = takeFirst(rightQueue);
				} else {
					// because not in, if equal left should move to next value
					leftLocal.close();
					rightLocal.close();
					leftLocal = takeFirst(leftQueue);
					rightLocal = takeFirst(rightQueue);
				}
			}
			nextHandler.rowEofResponse(null, isLeft, conn);
			HandlerTool.terminateHandlerTree(this);
		} catch (Exception e) {
			String msg = "notIn thread error, " + e.getLocalizedMessage();
			logger.warn(msg, e);
			session.onQueryError(msg.getBytes());
		} finally {
			if (leftLocal != null)
				leftLocal.close();
			if (rightLocal != null)
				rightLocal.close();
		}
	}

	private LocalResult takeFirst(FairLinkedBlockingDeque<LocalResult> deque) throws InterruptedException {
		/**
		 * 前提条件是这个方法是个单线程
		 */
		deque.waitUtilCount(1);
		LocalResult result = deque.peekFirst();
		RowDataPacket lastRow = result.getLastRow();
		if (lastRow.fieldCount == 0)
			return deque.takeFirst();
		else {
			deque.waitUtilCount(2);
			return deque.takeFirst();
		}
	}

	private void sendLeft(LocalResult leftRows, MySQLConnection conn) throws Exception {
		RowDataPacket leftRow = null;
		while ((leftRow = leftRows.next()) != null) {
			nextHandler.rowResponse(null, leftRow, isLeft, conn);
		}
	}

	private void addRowToDeque(RowDataPacket row, int columnCount, FairLinkedBlockingDeque<LocalResult> deque,
			RowDataComparator cmp) throws InterruptedException {
		LocalResult localResult = deque.peekLast();
		if (localResult != null) {
			RowDataPacket lastRow = localResult.getLastRow();
			if (lastRow.fieldCount == 0) {
				// 有可能是terminateThread添加的eof
				return;
			} else if (row.fieldCount > 0 && cmp.compare(lastRow, row) == 0) {
				localResult.add(row);
				return;
			} else {
				localResult.done();
			}
		}
		LocalResult newLocalResult = new UnSortedLocalResult(columnCount, pool, this.charset)
				.setMemSizeController(session.getJoinBufferMC());
		newLocalResult.add(row);
		if (row.fieldCount == 0)
			newLocalResult.done();
		deque.putLast(newLocalResult);
	}

	/**
	 * only for terminate.
	 * 
	 * @param row
	 * @param columnCount
	 * @param deque
	 * @throws InterruptedException
	 */
	private void addEndRowToDeque(RowDataPacket row, int columnCount, FairLinkedBlockingDeque<LocalResult> deque)
			throws InterruptedException {
		LocalResult newLocalResult = new UnSortedLocalResult(columnCount, pool, this.charset)
				.setMemSizeController(session.getJoinBufferMC());
		newLocalResult.add(row);
		newLocalResult.done();
		LocalResult localResult = deque.addOrReplaceLast(newLocalResult);
		if (localResult != null)
			localResult.close();
	}

	@Override
	protected void terminateThread() throws Exception {
		RowDataPacket eofRow = new RowDataPacket(0);
		addEndRowToDeque(eofRow, leftFieldPackets.size(), leftQueue);
		RowDataPacket eofRow2 = new RowDataPacket(0);
		addEndRowToDeque(eofRow2, rightFieldPackets.size(), rightQueue);
	}

	@Override
	protected void recycleResources() {
		clearDeque(this.leftQueue);
		clearDeque(this.rightQueue);
	}

	private void clearDeque(FairLinkedBlockingDeque<LocalResult> deque) {
		if (deque == null)
			return;
		LocalResult local = deque.poll();
		while (local != null) {
			local.close();
			local = deque.poll();
		}
	}
}