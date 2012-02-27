package com.alibaba.hbase.replication.domain;

import org.apache.hadoop.fs.Path;

import com.alibaba.hbase.replication.domain.HLogInfo.HLogType;


/**
 * HLog 用于 Zookeeper 序列化节点
 * @author zalot.zhaoh
 *
 */
public interface HLogZNode {
	public HLogType getType();
	public long getPos();
	public Path getPath();
	public int getVersion();
	public byte[] getData();
}
