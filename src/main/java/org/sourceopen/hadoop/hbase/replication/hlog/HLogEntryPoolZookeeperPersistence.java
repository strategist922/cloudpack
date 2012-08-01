package org.sourceopen.hadoop.hbase.replication.hlog;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.regionserver.wal.HLog;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.data.Stat;

import org.sourceopen.hadoop.hbase.replication.hlog.domain.HLogEntry;
import org.sourceopen.hadoop.hbase.replication.hlog.domain.HLogEntry.Type;
import org.sourceopen.hadoop.hbase.replication.hlog.domain.HLogEntryGroup;
import org.sourceopen.hadoop.hbase.replication.utility.ProducerConstants;
import org.sourceopen.hadoop.hbase.replication.zookeeper.RecoverableZooKeeper;

/**
 * HLogPersistence 持久化操作
 * 
 * @author zalot.zhaoh Mar 7, 2012 10:24:18 AM
 */
public class HLogEntryPoolZookeeperPersistence implements HLogEntryPoolPersistence {

    protected static final String  SPLIT = "|";
    protected static final Log     LOG   = LogFactory.getLog(HLogEntryPoolZookeeperPersistence.class);
    protected RecoverableZooKeeper zookeepr;
    protected String               baseDir;
    protected ThreadLocal<String>  uuid  = new ThreadLocal<String>();                                  ;

    // public ArrayList<ACL> perms;

    public String getName() {
        if (uuid.get() == null) {
            uuid.set(UUID.randomUUID().toString());
        }
        return uuid.get();
    }

    public void setZookeeper(RecoverableZooKeeper zoo) {
        this.zookeepr = zoo;
    }

    public HLogEntryPoolZookeeperPersistence(Configuration conf, RecoverableZooKeeper zoo) throws Exception{
        this.zookeepr = zoo;
        init(conf);
    }

    public void createEntry(HLogEntry entry) throws Exception {
        try {
            zookeepr.create(getEntryPath(entry), getEntryData(entry), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        } catch (NodeExistsException e) {
            LOG.warn("entry exist" + entry);
        }
    }

    public void deleteEntry(HLogEntry entry) throws Exception {
        String path = getEntryPath(entry);
        Stat stat = zookeepr.exists(path, false);
        if (stat != null) {
            zookeepr.delete(path, stat.getVersion());
        }
    }

    public void updateEntry(HLogEntry entry) throws Exception {
        if (entry.getType() == HLogEntry.Type.NOFOUND) {
            deleteEntry(entry);
            return;
        }
        String path = getEntryPath(entry);
        Stat stat = zookeepr.exists(path, false);
        if (stat != null) {
            zookeepr.setData(path, getEntryData(entry), stat.getVersion());
        }
    }

    @SuppressWarnings("unchecked")
    public List<HLogEntry> listEntry(String groupName) throws Exception {
        String path = getGroupPath(groupName);
        Stat stat = zookeepr.exists(path, false);
        if (stat == null) return Collections.EMPTY_LIST;
        List<HLogEntry> entrys = new ArrayList<HLogEntry>();
        List<String> ls = zookeepr.getChildren(path, false);
        HLogEntry entry = null;
        for (String name : ls) {
            entry = getHLogEntry(groupName, name);
            if (entry != null) entrys.add(entry);
        }
        return entrys;
    }

    public HLogEntry getHLogEntry(String groupName, String name) throws Exception {
        if (!HLog.validateHLogFilename(name)) {
            return null;
        }
        String path = getEntryPath(groupName, name);
        Stat stat = zookeepr.exists(path, false);
        if (stat != null) {
            HLogEntry entry = new HLogEntry(name);
            setEntryData(entry, zookeepr.getData(path, false, stat));
            return entry;
        }
        return null;
    }

    public List<String> listGroupName() throws KeeperException, InterruptedException {
        return zookeepr.getChildren(baseDir, false);
    }

    public void createGroup(HLogEntryGroup group, boolean createChild) throws Exception {
        try {
            zookeepr.create(getGroupPath(group.getGroupName()), getGroupData(group), Ids.OPEN_ACL_UNSAFE,
                            CreateMode.PERSISTENT);
        } catch (NodeExistsException e) {
            LOG.warn("group exist" + group);
        }
        if (createChild) {
            for (HLogEntry entry : group.getEntrys()) {
                createEntry(entry);
            }
        }
    }

    public HLogEntryGroup getGroupByName(String groupName, boolean getChild) throws Exception {
        String path = getGroupPath(groupName);
        Stat stat = zookeepr.exists(path, false);
        if (stat == null) return null;
        HLogEntryGroup group = new HLogEntryGroup(groupName);
        setGroupData(group, zookeepr.getData(path, false, stat));
        if (getChild) {
            List<String> ls = zookeepr.getChildren(path, false);
            HLogEntry entry = null;
            for (String name : ls) {
                entry = getHLogEntry(groupName, name);
                if (entry != null) group.put(entry);
            }
        }
        return group;
    }

    public void updateGroup(HLogEntryGroup group, boolean updateChild) throws Exception {
        String path = getGroupPath(group.getGroupName());
        Stat stat = zookeepr.exists(path, false);
        if (stat != null) {
            try {
                zookeepr.setData(path, getGroupData(group), stat.getVersion());
                // set time error ( no pb )
            } catch (Exception e) {
            }
            if (updateChild) {
                HLogEntry tmpEntry;
                for (HLogEntry entry : group.getEntrys()) {
                    tmpEntry = getHLogEntry(entry.getGroupName(), entry.getName());
                    if (tmpEntry == null) {
                        createEntry(entry);
                    } else {
                        // 更新和修复数据
                        if (tmpEntry.getType() != HLogEntry.Type.END) {
                            if (tmpEntry.getType() != entry.getType()) {
                                tmpEntry.setType(entry.getType());
                                updateEntry(tmpEntry);
                            }
                        } else {
                            if (tmpEntry.getPos() <= 0) {
                                tmpEntry.setType(entry.getType());
                                updateEntry(tmpEntry);
                            }
                        }
                    }
                }
            }
        }
    }

    public void createOrUpdateGroup(HLogEntryGroup group, boolean updateChild) throws Exception {
        HLogEntryGroup tmpGroup = getGroupByName(group.getGroupName(), false);
        if (tmpGroup == null) {
            createGroup(group, updateChild);
        } else {
            updateGroup(group, updateChild);
        }
    }

    public boolean isLockGroup(String groupName) throws Exception {
        Stat stat = getLockGroupStat(groupName);
        if (stat != null) {
            return true;
        }
        return false;
    }

    public boolean lockGroup(String groupName) throws Exception {
        return lockGroup(groupName, false);
    }

    protected boolean lockGroup(String groupName, boolean is) throws Exception {
        if (!isLockGroup(groupName)) {
            try {
                zookeepr.create(getGroupLockPath(groupName), getGroupLockData(), Ids.OPEN_ACL_UNSAFE,
                                CreateMode.EPHEMERAL);
                return true;
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    protected byte[] getGroupLockData() {
        return Bytes.toBytes(getName());
    }

    protected String setGroupLockData(byte[] data) {
        return Bytes.toString(data);
    }

    public void unlockGroup(String groupName) throws Exception {
        Stat stat = getLockGroupStat(groupName);
        if (stat == null) return;
        try {
            String path = getGroupLockPath(groupName);
            zookeepr.delete(path, stat.getVersion());
        } catch (Exception e) {
            return;
        }
    }

    private byte[] getEntryData(HLogEntry entry) {
        String data = entry.getPos() + SPLIT + entry.getLastVerifiedPos() + SPLIT + entry.getType().getTypeValue()
                      + SPLIT + entry.getLastReadtime();
        return Bytes.toBytes(data);
    }

    /**
     * 请与 getEntryData 保持一致
     * 
     * @param entry
     * @param data
     */
    private void setEntryData(HLogEntry entry, byte[] data) {
        if (data != null && entry != null) {
            String dataString = Bytes.toString(data);
            String[] idxs = StringUtils.split(dataString, SPLIT);
            setEntryDataVersion(idxs, entry);
        }
    }

    protected static void setEntryDataVersion(String[] idxs, HLogEntry entry) {
        // len 3
        if (idxs.length == 3) {
            entry.setPos(Long.parseLong(idxs[0]));
            entry.setType(Type.toType(Integer.valueOf(idxs[1])));
            entry.setLastReadtime(Long.parseLong(idxs[2]));
        }
        // len4
        if (idxs.length == 4) {
            entry.setPos(Long.parseLong(idxs[0]));
            entry.setLastVerifiedPos(Long.parseLong(idxs[1]));
            entry.setType(Type.toType(Integer.valueOf(idxs[2])));
            entry.setLastReadtime(Long.parseLong(idxs[3]));
        }
    }

    private String getEntryPath(String groupName, String name) {
        return baseDir + "/" + groupName + "/" + name;
    }

    private String getEntryPath(HLogEntry entry) {
        return getEntryPath(entry.getGroupName(), entry.getName());
    }

    //
    //
    //
    protected byte[] getGroupData(HLogEntryGroup group) {
        return Bytes.toBytes(group.getLastOperatorTime());
    }

    /**
     * 请与 getGroupData 保持一致性
     * 
     * @param entry
     * @param data
     */
    protected void setGroupData(HLogEntryGroup entry, byte[] data) {
        if (entry != null && data != null) {
            entry.setLastOperatorTime(Bytes.toLong(data));
        }
    }

    protected String getGroupPath(String groupName) {
        return baseDir + "/" + groupName;
    }

    protected String getGroupLockPath(String groupName) {
        return baseDir + "/" + groupName + ProducerConstants.ZOO_PERSISTENCE_HLOG_GROUP_LOCK;
    }

    protected Stat getGroupStat(HLogEntryGroup group) throws Exception {
        String lockpath = getGroupPath(group.getGroupName());
        return zookeepr.exists(lockpath, false);
    }

    protected Stat getLockGroupStat(String groupName) throws Exception {
        HLogEntryGroup group = getGroupByName(groupName, false);
        if (group != null) {
            String lockpath = getGroupLockPath(groupName);
            Stat stat = zookeepr.exists(lockpath, false);
            return stat;
        }
        return null;
    }

    protected long getLockSeq(String lockPath, String seqPath) {
        try {
            String tmpLockPath = lockPath + ManagementFactory.getRuntimeMXBean().getName();
            int idx = seqPath.indexOf(tmpLockPath);
            if (idx == 0) {
                return Long.parseLong(seqPath.substring(tmpLockPath.length() + 1, seqPath.length()));
            }
        } catch (Exception e) {
        }
        return -1;
    }

    @Override
    public void init(Configuration conf) throws Exception {
        String rootDir = conf.get(ProducerConstants.CONFKEY_ZOO_ROOT, ProducerConstants.ZOO_ROOT);
        Stat stat = zookeepr.exists(rootDir, false);
        if (stat == null) {
            zookeepr.create(rootDir, null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        }
        baseDir = rootDir + ProducerConstants.ZOO_PERSISTENCE_HLOG_GROUP;

        stat = zookeepr.exists(baseDir, false);
        if (stat == null) {
            zookeepr.create(baseDir, null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        }
    }

    @Override
    public boolean isMeLockGroup(String groupName) throws Exception {
        String path = getGroupLockPath(groupName);
        Stat stat = zookeepr.exists(path, false);
        if (stat != null) {
            String name = null;
            try {
                name = setGroupLockData(zookeepr.getData(path, false, stat));
            } catch (Exception e) {
                return false;
            }
            if (name != null) {
                if (name.equals(getName())) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void deleteGroup(String groupName) throws Exception {
        String groupPath = getGroupPath(groupName);
        Stat stat = zookeepr.exists(groupPath, false);
        if (stat != null) {
            Stat cstat;
            String childPath;
            for (String child : zookeepr.getChildren(groupPath, false)) {
                childPath = groupPath + "/" + child;
                cstat = zookeepr.exists(childPath, false);
                if (cstat != null) zookeepr.delete(childPath, cstat.getVersion());
            }
            stat = zookeepr.exists(groupPath, false);
            zookeepr.delete(groupPath, stat.getVersion());
        }
    }
}