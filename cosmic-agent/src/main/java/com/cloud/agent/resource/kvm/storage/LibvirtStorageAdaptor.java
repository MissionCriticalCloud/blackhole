package com.cloud.agent.resource.kvm.storage;

import com.cloud.agent.resource.kvm.LibvirtConnection;
import com.cloud.agent.resource.kvm.storage.utils.QemuImg;
import com.cloud.agent.resource.kvm.storage.utils.QemuImgException;
import com.cloud.agent.resource.kvm.storage.utils.QemuImgFile;
import com.cloud.agent.resource.kvm.xml.LibvirtSecretDef;
import com.cloud.agent.resource.kvm.xml.LibvirtSecretDef.Usage;
import com.cloud.agent.resource.kvm.xml.LibvirtStoragePoolDef;
import com.cloud.agent.resource.kvm.xml.LibvirtStoragePoolDef.AuthenticationType;
import com.cloud.agent.resource.kvm.xml.LibvirtStoragePoolDef.PoolType;
import com.cloud.agent.resource.kvm.xml.LibvirtStoragePoolXmlParser;
import com.cloud.agent.resource.kvm.xml.LibvirtStorageVolumeDef;
import com.cloud.agent.resource.kvm.xml.LibvirtStorageVolumeXmlParser;
import com.cloud.legacymodel.exceptions.CloudRuntimeException;
import com.cloud.model.enumeration.ImageFormat;
import com.cloud.model.enumeration.PhysicalDiskFormat;
import com.cloud.model.enumeration.PreallocationType;
import com.cloud.model.enumeration.StoragePoolType;
import com.cloud.model.enumeration.StorageProvisioningType;
import com.cloud.utils.script.Script;
import com.cloud.utils.storage.StorageLayer;

import java.io.File;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.ceph.rados.IoCTX;
import com.ceph.rados.Rados;
import com.ceph.rados.exceptions.RadosException;
import com.ceph.rbd.Rbd;
import com.ceph.rbd.RbdException;
import com.ceph.rbd.RbdImage;
import com.ceph.rbd.jna.RbdImageInfo;
import com.ceph.rbd.jna.RbdSnapInfo;
import org.apache.commons.codec.binary.Base64;
import org.libvirt.Connect;
import org.libvirt.LibvirtException;
import org.libvirt.Secret;
import org.libvirt.StoragePool;
import org.libvirt.StoragePoolInfo.StoragePoolState;
import org.libvirt.StorageVol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LibvirtStorageAdaptor implements StorageAdaptor {

    private final Logger logger = LoggerFactory.getLogger(LibvirtStorageAdaptor.class);

    private final StorageLayer storageLayer;
    private final String mountPoint = "/mnt";

    private final int rbdFeatures = 1; /* Feature 1<<0 means layering in RBD format 2 */
    private final int rbdOrder = 0; /* Order 0 means 4MB blocks (the default) */

    LibvirtStorageAdaptor(final StorageLayer storage) {
        this.storageLayer = storage;
    }

    @Override
    public KvmStoragePool getStoragePool(final String uuid) {
        return this.getStoragePool(uuid, false);
    }

    @Override
    public KvmStoragePool getStoragePool(final String uuid, final boolean refreshInfo) {
        this.logger.info("Trying to fetch storage pool " + uuid + " from libvirt");
        final StoragePool storage;
        try {
            final Connect conn = LibvirtConnection.getConnection();
            storage = conn.storagePoolLookupByUUIDString(uuid);

            if (storage.getInfo().state != StoragePoolState.VIR_STORAGE_POOL_RUNNING) {
                this.logger.warn("Storage pool " + uuid + " is not in running state. Attempting to start it.");
                storage.create(0);
            }
            final LibvirtStoragePoolDef spd = getStoragePoolDef(storage);
            if (spd == null) {
                throw new CloudRuntimeException("Unable to parse the storage pool definition for storage pool " + uuid);
            }
            StoragePoolType type = null;
            if (spd.getPoolType() == LibvirtStoragePoolDef.PoolType.NETFS) {
                type = StoragePoolType.NetworkFilesystem;
            } else if (spd.getPoolType() == LibvirtStoragePoolDef.PoolType.DIR) {
                type = StoragePoolType.Filesystem;
            } else if (spd.getPoolType() == LibvirtStoragePoolDef.PoolType.RBD) {
                type = StoragePoolType.RBD;
            } else if (spd.getPoolType() == LibvirtStoragePoolDef.PoolType.LOGICAL) {
                type = StoragePoolType.LVM;
            } else if (spd.getPoolType() == LibvirtStoragePoolDef.PoolType.GLUSTERFS) {
                type = StoragePoolType.Gluster;
            }

            final LibvirtStoragePool pool = new LibvirtStoragePool(uuid, storage.getName(), type, this, storage);

            if (pool.getType() != StoragePoolType.RBD) {
                pool.setLocalPath(spd.getTargetPath());
            } else {
                pool.setLocalPath("");
            }

            if (pool.getType() == StoragePoolType.RBD
                    || pool.getType() == StoragePoolType.Gluster) {
                pool.setSourceHost(spd.getSourceHost());
                pool.setSourcePort(spd.getSourcePort());
                pool.setSourceDir(spd.getSource());
                final String authUsername = spd.getAuthUsername();
                if (authUsername != null) {
                    final Secret secret = conn.secretLookupByUUIDString(spd.getSecretUuid());
                    final String secretValue = new String(Base64.encodeBase64(secret.getByteValue()), Charset.defaultCharset());
                    pool.setAuthUsername(authUsername);
                    pool.setAuthSecret(secretValue);
                }
            }

            if (refreshInfo) {
                this.logger.info("Asking libvirt to refresh storage pool " + uuid);
                pool.refresh();
            }
            pool.setCapacity(storage.getInfo().capacity);
            pool.setUsed(storage.getInfo().allocation);
            pool.setAvailable(storage.getInfo().available);

            this.logger.debug("Succesfully refreshed pool " + uuid
                    + " Capacity: " + storage.getInfo().capacity
                    + " Used: " + storage.getInfo().allocation
                    + " Available: " + storage.getInfo().available);

            return pool;
        } catch (final LibvirtException e) {
            this.logger.debug("Could not find storage pool " + uuid + " in libvirt");
            throw new CloudRuntimeException(e.toString(), e);
        }
    }

    @Override
    public KvmPhysicalDisk getPhysicalDisk(final String volumeUuid, final KvmStoragePool pool) {
        final LibvirtStoragePool libvirtPool = (LibvirtStoragePool) pool;

        try {
            final StorageVol vol = getVolume(libvirtPool.getPool(), volumeUuid);
            final KvmPhysicalDisk disk;
            final LibvirtStorageVolumeDef voldef = getStorageVolumeDef(vol);
            disk = new KvmPhysicalDisk(vol.getPath(), vol.getName(), pool);
            disk.setSize(vol.getInfo().allocation);
            disk.setVirtualSize(vol.getInfo().capacity);

            if (pool.getType() == StoragePoolType.RBD) {
                disk.setFormat(PhysicalDiskFormat.RAW);
            } else if (pool.getType() == StoragePoolType.NetworkFilesystem) {
                final QemuImg qemuImg = new QemuImg(60000);
                final Map<String, String> info = qemuImg.info(new QemuImgFile(disk.getPath()));
                disk.setFormat(PhysicalDiskFormat.valueOf(info.get("file_format").toUpperCase()));
            } else if (voldef.getFormat() == null) {
                disk.setFormat(pool.getDefaultFormat());
            } else if (voldef.getFormat() == LibvirtStorageVolumeDef.VolumeFormat.QCOW2) {
                disk.setFormat(PhysicalDiskFormat.QCOW2);
            } else if (voldef.getFormat() == LibvirtStorageVolumeDef.VolumeFormat.RAW) {
                disk.setFormat(PhysicalDiskFormat.RAW);
            }
            return disk;
        } catch (final LibvirtException | QemuImgException e) {
            this.logger.debug("Failed to get physical disk:", e);
            throw new CloudRuntimeException(e.toString());
        }
    }

    @Override
    public KvmStoragePool createStoragePool(final String name, final String host, final int port, String path, final String userInfo, final StoragePoolType type) {
        this.logger.info("Attempting to create storage pool " + name + " (" + type.toString() + ") in libvirt");

        StoragePool sp;
        final Connect conn;
        try {
            conn = LibvirtConnection.getConnection();
        } catch (final LibvirtException e) {
            throw new CloudRuntimeException(e.toString());
        }

        try {
            sp = conn.storagePoolLookupByUUIDString(name);
            if (sp != null && sp.isActive() == 0) {
                sp.undefine();
                sp = null;
                this.logger.info("Found existing defined storage pool " + name + ". It wasn't running, so we undefined it.");
            }
            if (sp != null) {
                this.logger.info("Found existing defined storage pool " + name + ", using it.");
            }
        } catch (final LibvirtException e) {
            sp = null;
            this.logger.warn("Storage pool " + name + " was not found running in libvirt. Need to create it.");
        }

        // libvirt strips trailing slashes off of path, we will too in order to match
        // existing paths
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        if (sp == null) {
            // see if any existing pool by another name is using our storage path.
            // if anyone is, undefine the pool so we can define it as requested.
            // This should be safe since a pool in use can't be removed, and no
            // volumes are affected by unregistering the pool with libvirt.
            this.logger.info("Didn't find an existing storage pool " + name + " by UUID, checking for pools with duplicate paths");

            try {
                final String[] poolnames = conn.listStoragePools();
                for (final String poolname : poolnames) {
                    this.logger.debug("Checking path of existing pool " + poolname + " against pool we want to create");
                    final StoragePool p = conn.storagePoolLookupByName(poolname);
                    final LibvirtStoragePoolDef pdef = getStoragePoolDef(p);

                    final String targetPath = pdef.getTargetPath();
                    if (targetPath != null && targetPath.equals(path)) {
                        this.logger.debug("Storage pool utilizing path '" + path + "' already exists as pool " + poolname
                                + ", undefining so we can re-define with correct name " + name);
                        if (p.isPersistent() == 1) {
                            p.destroy();
                            p.undefine();
                        } else {
                            p.destroy();
                        }
                    }
                }
            } catch (final LibvirtException e) {
                this.logger.error("Failure in attempting to see if an existing storage pool might be using the path "
                        + "of the pool to be created:" + e);
            }

            this.logger.debug("Attempting to create storage pool " + name);

            if (type == StoragePoolType.NetworkFilesystem) {
                try {
                    sp = createNetfsStoragePool(PoolType.NETFS, conn, name, host, path);
                } catch (final LibvirtException e) {
                    this.logger.error("Failed to create netfs mount: " + host + ":" + path, e);
                    this.logger.error(Arrays.toString(e.getStackTrace()));
                    throw new CloudRuntimeException(e.toString());
                }
            } else if (type == StoragePoolType.Gluster) {
                try {
                    sp = createNetfsStoragePool(PoolType.GLUSTERFS, conn, name, host, path);
                } catch (final LibvirtException e) {
                    this.logger.error("Failed to create glusterfs mount: " + host + ":" + path, e);
                    this.logger.error(Arrays.toString(e.getStackTrace()));
                    throw new CloudRuntimeException(e.toString());
                }
            } else if (type == StoragePoolType.SharedMountPoint || type == StoragePoolType.Filesystem) {
                sp = createSharedStoragePool(conn, name, host, path);
            } else if (type == StoragePoolType.RBD) {
                sp = createRbdStoragePool(conn, name, host, port, userInfo, path);
            } else if (type == StoragePoolType.CLVM) {
                sp = createClvmStoragePool(conn, name, host, path);
            } else if (type == StoragePoolType.LVM) {
                sp = createLVMStoragePool(conn, name, path);
            }
        }

        if (sp == null) {
            throw new CloudRuntimeException("Failed to create storage pool: " + name);
        }

        try {
            if (sp.isActive() == 0) {
                this.logger.debug("Attempting to activate pool " + name);
                sp.create(0);
            }

            return getStoragePool(name);
        } catch (final LibvirtException e) {
            final String error = e.toString();
            if (error.contains("Storage source conflict")) {
                throw new CloudRuntimeException("A pool matching this location already exists in libvirt, "
                        + " but has a different UUID/Name. Cannot create new pool without first "
                        + " removing it. Check for inactive pools via 'virsh pool-list --all'. "
                        + error);
            } else {
                throw new CloudRuntimeException(error);
            }
        }
    }

    private StoragePool createNetfsStoragePool(final PoolType fsType, final Connect conn, final String uuid, final String host, final String path)
            throws LibvirtException {
        final String targetPath = this.mountPoint + File.separator + uuid;
        final LibvirtStoragePoolDef spd = new LibvirtStoragePoolDef(fsType, uuid, uuid, host, path, targetPath);
        this.storageLayer.mkdir(targetPath);
        StoragePool sp;
        try {
            this.logger.debug(spd.toString());
            sp = conn.storagePoolCreateXML(spd.toString(), 0);
            return sp;
        } catch (final LibvirtException e) {
            this.logger.error(e.toString());
            // if error is that pool is mounted, try to handle it
            if (e.toString().contains("already mounted")) {
                this.logger.error("Attempting to unmount old mount libvirt is unaware of at " + targetPath);
                final String result = Script.runSimpleBashScript("umount -l " + targetPath);
                if (result == null) {
                    this.logger.error("Succeeded in unmounting " + targetPath);
                    try {
                        sp = conn.storagePoolCreateXML(spd.toString(), 0);
                        this.logger.error("Succeeded in redefining storage");
                        return sp;
                    } catch (final LibvirtException l) {
                        this.logger.error("Target was already mounted, unmounted it but failed to redefine storage:" + l);
                    }
                } else {
                    this.logger.error("Failed in unmounting and redefining storage");
                }
            } else {
                this.logger.error("Internal error occurred when attempting to mount: specified path may be invalid");
                throw e;
            }
            return null;
        }
    }

    private StoragePool createSharedStoragePool(final Connect conn, final String uuid, final String host, final String path) {
        if (!this.storageLayer.exists(path)) {
            this.logger.error(path + " does not exists. Check local.storage.path in agent.properties.");
            return null;
        }
        final LibvirtStoragePoolDef spd = new LibvirtStoragePoolDef(PoolType.DIR, uuid, uuid, host, path, path);
        final StoragePool sp;
        try {
            this.logger.debug(spd.toString());
            sp = conn.storagePoolCreateXML(spd.toString(), 0);
            return sp;
        } catch (final LibvirtException e) {
            this.logger.error(e.toString());
            return null;
        }
    }

    private StoragePool createRbdStoragePool(final Connect conn, final String uuid, final String host, final int port, final String userInfo,
                                             final String path) {

        final LibvirtStoragePoolDef storagePoolDefinition;
        final StoragePool storagePool;
        Secret secretString = null;

        final String[] userInfoTemp = userInfo.split(":");
        if (userInfoTemp.length == 2) {
            final LibvirtSecretDef sd = new LibvirtSecretDef(Usage.CEPH, uuid);

            sd.setCephName(userInfoTemp[0] + "@" + host + ":" + port + "/" + path);

            try {
                this.logger.debug(sd.toString());
                secretString = conn.secretDefineXML(sd.toString());
                secretString.setValue(Base64.decodeBase64(userInfoTemp[1]));
            } catch (final LibvirtException e) {
                this.logger.error("Failed to define the libvirt secret: " + e.toString());
                if (secretString != null) {
                    try {
                        secretString.undefine();
                        secretString.free();
                    } catch (final LibvirtException l) {
                        this.logger.error("Failed to undefine the libvirt secret: " + l.toString());
                    }
                }
                return null;
            }
            storagePoolDefinition = new LibvirtStoragePoolDef(PoolType.RBD, uuid, uuid, host, port, path, userInfoTemp[0],
                    AuthenticationType.CEPH, uuid);
        } else {
            storagePoolDefinition = new LibvirtStoragePoolDef(PoolType.RBD, uuid, uuid, host, port, path, "");
        }

        try {
            this.logger.debug(storagePoolDefinition.toString());
            storagePool = conn.storagePoolCreateXML(storagePoolDefinition.toString(), 0);
            return storagePool;
        } catch (final LibvirtException e) {
            this.logger.error("Failed to create RBD storage pool: " + e.toString());

            if (secretString != null) {
                try {
                    this.logger.error("Failed to create the RBD storage pool, cleaning up the libvirt secret");
                    secretString.undefine();
                    secretString.free();
                } catch (final LibvirtException se) {
                    this.logger.error("Failed to remove the libvirt secret: " + se.toString());
                }
            }

            return null;
        }
    }

    private StoragePool createLVMStoragePool(final Connect conn, final String uuid, final String path) {
        final LibvirtStoragePoolDef libvirtStoragePoolDef = new LibvirtStoragePoolDef();

        libvirtStoragePoolDef.setPoolName(uuid);
        libvirtStoragePoolDef.setUuid(uuid);
        libvirtStoragePoolDef.setTargetPath("/dev/" + path);
        libvirtStoragePoolDef.setSource(path);
        libvirtStoragePoolDef.setPoolType(PoolType.LOGICAL);

        try {
            this.logger.debug(libvirtStoragePoolDef.toString());
            return conn.storagePoolCreateXML(libvirtStoragePoolDef.toString(), 0);
        } catch (final LibvirtException e) {
            this.logger.error("Unable to add LVM storage pool: ", e);
            return null;
        }
    }

    private StoragePool createClvmStoragePool(final Connect conn, final String uuid, final String host, final String path) {
        final String volgroupPath = "/dev/" + path;
        String volgroupName = path;
        volgroupName = volgroupName.replaceFirst("/", "");

        final LibvirtStoragePoolDef spd = new LibvirtStoragePoolDef(PoolType.LOGICAL, volgroupName, uuid, host, volgroupPath, volgroupPath);

        try {
            this.logger.debug(spd.toString());
            return conn.storagePoolCreateXML(spd.toString(), 0);
        } catch (final LibvirtException e) {
            this.logger.error("Unable to add CLVM storage pool: ", e);
            return null;
        }
    }

    @Override
    public boolean deleteStoragePool(final String uuid) {
        this.logger.info("Attempting to remove storage pool " + uuid + " from libvirt");
        final Connect conn;
        try {
            conn = LibvirtConnection.getConnection();
        } catch (final LibvirtException e) {
            throw new CloudRuntimeException(e.toString());
        }

        final StoragePool storagePool;
        Secret secretString = null;

        try {
            storagePool = conn.storagePoolLookupByUUIDString(uuid);
        } catch (final LibvirtException e) {
            this.logger.warn("Storage pool " + uuid + " doesn't exist in libvirt. Assuming it is already removed");
            return true;
        }

        /*
         * Some storage pools, like RBD also have 'secret' information stored in libvirt Destroy them if they exist
         */
        try {
            secretString = conn.secretLookupByUUIDString(uuid);
        } catch (final LibvirtException e) {
            this.logger.info("Storage pool " + uuid + " has no corresponding secret. Not removing any secret.");
        }

        try {
            if (storagePool.isPersistent() == 1) {
                storagePool.destroy();
                storagePool.undefine();
            } else {
                storagePool.destroy();
            }
            storagePool.free();
            if (secretString != null) {
                secretString.undefine();
                secretString.free();
            }

            this.logger.info("Storage pool " + uuid + " was succesfully removed from libvirt.");

            return true;
        } catch (final LibvirtException e) {
            // handle ebusy error when pool is quickly destroyed
            if (e.toString().contains("exit status 16")) {
                final String targetPath = this.mountPoint + File.separator + uuid;
                this.logger.error(
                        "deleteStoragePool removed pool from libvirt, but libvirt had trouble unmounting the pool. "
                                + "Trying umount location " + targetPath + " again in a few seconds");
                final String result = Script.runSimpleBashScript("sleep 5 && umount " + targetPath);
                if (result == null) {
                    this.logger.error("Succeeded in unmounting " + targetPath);
                    return true;
                }
                this.logger.error("Failed to unmount " + targetPath);
            }
            throw new CloudRuntimeException(e.toString(), e);
        }
    }

    @Override
    public boolean deleteStoragePool(final KvmStoragePool pool) {
        return deleteStoragePool(pool.getUuid());
    }

    @Override
    public KvmPhysicalDisk createPhysicalDisk(final String name, final KvmStoragePool pool, final PhysicalDiskFormat format, final StorageProvisioningType provisioningType,
                                              final long size) {

        this.logger.info("Attempting to create volume " + name + " (" + pool.getType().toString() + ") in pool " + pool.getUuid() + " with size " + size);

        switch (pool.getType()) {
            case RBD:
                return createPhysicalDiskOnRbd(name, pool, size);
            case NetworkFilesystem:
            case Filesystem:
                switch (format) {
                    case QCOW2:
                        return createPhysicalDiskByQemuImg(name, pool, format, provisioningType, size);
                    case RAW:
                        return createPhysicalDiskByQemuImg(name, pool, format, provisioningType, size);
                    case DIR:
                        return createPhysicalDiskByLibVirt(name, pool, format, size);
                    case TAR:
                        return createPhysicalDiskByLibVirt(name, pool, format, size);
                    default:
                        throw new CloudRuntimeException("Unexpected disk format is specified.");
                }
            case LVM:
                // LVM is block -> So format will always be RAW.
                return createPhysicalDiskByLibVirt(name, pool, PhysicalDiskFormat.RAW, size);
            default:
                return createPhysicalDiskByLibVirt(name, pool, format, size);
        }
    }

    private KvmPhysicalDisk createPhysicalDiskOnRbd(final String name, final KvmStoragePool pool, final long size) {
        final String volPath;

        try {
            this.logger.info("Creating RBD image " + pool.getSourceDir() + "/" + name + " with size " + size);

            final Rados r = new Rados(pool.getAuthUserName());
            r.confSet("mon_host", pool.getSourceHost() + ":" + pool.getSourcePort());
            r.confSet("key", pool.getAuthSecret());
            r.confSet("client_mount_timeout", "30");
            r.connect();
            this.logger.debug("Succesfully connected to Ceph cluster at " + r.confGet("mon_host"));

            final IoCTX io = r.ioCtxCreate(pool.getSourceDir());
            final Rbd rbd = new Rbd(io);
            rbd.create(name, size, this.rbdFeatures, this.rbdOrder);

            r.ioCtxDestroy(io);
        } catch (final RadosException | RbdException e) {
            throw new CloudRuntimeException(e.toString());
        }

        volPath = pool.getSourceDir() + "/" + name;
        final KvmPhysicalDisk disk = new KvmPhysicalDisk(volPath, name, pool);
        disk.setFormat(PhysicalDiskFormat.RAW);
        disk.setSize(size);
        disk.setVirtualSize(size);
        return disk;
    }

    private KvmPhysicalDisk createPhysicalDiskByQemuImg(final String name, final KvmStoragePool pool, final PhysicalDiskFormat format,
                                                        final StorageProvisioningType provisioningType, final long
            size) {
        final String volPath = pool.getLocalPath() + "/" + name;
        long virtualSize = 0;
        long actualSize = 0;

        final int timeout = 0;

        final QemuImgFile destFile = new QemuImgFile(volPath);
        destFile.setFormat(format);
        destFile.setSize(size);
        final QemuImg qemu = new QemuImg(timeout);
        final Map<String, String> options = new HashMap<>();
        if (pool.getType() == StoragePoolType.NetworkFilesystem) {
            options.put("preallocation", PreallocationType.getPreallocationType(provisioningType).toString().toLowerCase());
        }

        try {
            qemu.create(destFile, options);
            final Map<String, String> info = qemu.info(destFile);
            virtualSize = Long.parseLong(info.get("virtual_size"));
            actualSize = new File(destFile.getFileName()).length();
        } catch (final QemuImgException e) {
            this.logger.error("Failed to create " + volPath + " due to a failed executing of qemu-img: " + e.getMessage());
            throw new CloudRuntimeException(e.toString());
        }

        final KvmPhysicalDisk disk = new KvmPhysicalDisk(volPath, name, pool);
        disk.setFormat(format);
        disk.setSize(actualSize);
        disk.setVirtualSize(virtualSize);
        return disk;
    }

    private KvmPhysicalDisk createPhysicalDiskByLibVirt(final String name, final KvmStoragePool pool, final PhysicalDiskFormat format, final long size) {
        final LibvirtStoragePool libvirtPool = (LibvirtStoragePool) pool;
        final StoragePool virtPool = libvirtPool.getPool();
        final LibvirtStorageVolumeDef.VolumeFormat libvirtformat = LibvirtStorageVolumeDef.VolumeFormat.getFormat(format);

        final String volPath;
        final String volName;
        final long volAllocation;
        final long volCapacity;

        final LibvirtStorageVolumeDef volDef = new LibvirtStorageVolumeDef(name, size, libvirtformat, null, null, null);
        this.logger.debug(volDef.toString());
        try {
            final StorageVol vol = virtPool.storageVolCreateXML(volDef.toString(), 0);
            volPath = vol.getPath();
            volName = vol.getName();
            volAllocation = vol.getInfo().allocation;
            volCapacity = vol.getInfo().capacity;
        } catch (final LibvirtException e) {
            throw new CloudRuntimeException(e.toString());
        }

        final KvmPhysicalDisk disk = new KvmPhysicalDisk(volPath, volName, pool);
        disk.setFormat(format);
        disk.setSize(volAllocation);
        disk.setVirtualSize(volCapacity);
        return disk;
    }

    @Override
    public boolean connectPhysicalDisk(final String name, final KvmStoragePool pool, final Map<String, String> details) {
        // this is for managed storage that needs to prep disks prior to use
        return true;
    }

    @Override
    public boolean disconnectPhysicalDisk(final String uuid, final KvmStoragePool pool) {
        // this is for managed storage that needs to cleanup disks after use
        return true;
    }

    @Override
    public boolean disconnectPhysicalDiskByPath(final String localPath) {
        // we've only ever cleaned up ISOs that are NFS mounted
        String poolUuid = null;
        if (localPath != null && localPath.startsWith(this.mountPoint) && localPath.endsWith(".iso")) {
            final String[] token = localPath.split("/");

            if (token.length > 3) {
                poolUuid = token[2];
            }
        } else {
            return false;
        }

        if (poolUuid == null) {
            return false;
        }

        try {
            final Connect conn = LibvirtConnection.getConnection();

            conn.storagePoolLookupByUUIDString(poolUuid);

            deleteStoragePool(poolUuid);

            return true;
        } catch (final LibvirtException | CloudRuntimeException ex) {
            return false;
        }
    }

    @Override
    public boolean deletePhysicalDisk(final String uuid, final KvmStoragePool pool, final ImageFormat format) {

        this.logger.info("Attempting to remove volume " + uuid + " from pool " + pool.getUuid());

        if (pool.getType() == StoragePoolType.RBD) {
            try {
                this.logger.info("Unprotecting and Removing RBD snapshots of image " + pool.getSourceDir() + "/" + uuid
                        + " prior to removing the image");

                final Rados r = new Rados(pool.getAuthUserName());
                r.confSet("mon_host", pool.getSourceHost() + ":" + pool.getSourcePort());
                r.confSet("key", pool.getAuthSecret());
                r.confSet("client_mount_timeout", "30");
                r.connect();
                this.logger.debug("Succesfully connected to Ceph cluster at " + r.confGet("mon_host"));

                final IoCTX io = r.ioCtxCreate(pool.getSourceDir());
                final Rbd rbd = new Rbd(io);
                final RbdImage image = rbd.open(uuid);
                this.logger.debug("Fetching list of snapshots of RBD image " + pool.getSourceDir() + "/" + uuid);
                final List<RbdSnapInfo> snaps = image.snapList();
                for (final RbdSnapInfo snap : snaps) {
                    if (image.snapIsProtected(snap.name)) {
                        this.logger.debug("Unprotecting snapshot " + pool.getSourceDir() + "/" + uuid + "@" + snap.name);
                        image.snapUnprotect(snap.name);
                    } else {
                        this.logger.debug("Snapshot " + pool.getSourceDir() + "/" + uuid + "@" + snap.name + " is not protected.");
                    }
                    this.logger.debug("Removing snapshot " + pool.getSourceDir() + "/" + uuid + "@" + snap.name);
                    image.snapRemove(snap.name);
                }

                rbd.close(image);
                r.ioCtxDestroy(io);

                this.logger.info("Succesfully unprotected and removed any remaining snapshots (" + snaps.size() + ") of "
                        + pool.getSourceDir() + "/" + uuid + " Continuing to remove the RBD image");
            } catch (final RadosException | RbdException e) {
                throw new CloudRuntimeException(e.toString());
            }
        }

        final LibvirtStoragePool libvirtPool = (LibvirtStoragePool) pool;
        try {
            final StorageVol vol = getVolume(libvirtPool.getPool(), uuid);
            this.logger.debug("Instructing libvirt to remove volume " + uuid + " from pool " + pool.getUuid());
            if (ImageFormat.DIR.equals(format)) {
                deleteDirVol(vol);
            } else {
                deleteVol(vol);
            }
            vol.free();
            return true;
        } catch (final LibvirtException e) {
            throw new CloudRuntimeException(e.toString());
        }
    }

    private StorageVol getVolume(final StoragePool pool, final String volName) {
        StorageVol vol = null;

        try {
            vol = pool.storageVolLookupByName(volName);
        } catch (final LibvirtException e) {
            this.logger.debug("Could not find volume " + volName + ": " + e.getMessage());
        }

        /**
         * The volume was not found in the storage pool This can happen when a volume has just been created on a different
         * host and since then the libvirt storage pool has not been refreshed.
         */
        if (vol == null) {
            try {
                this.logger.debug("Refreshing storage pool " + pool.getName());
                refreshPool(pool);
            } catch (final LibvirtException e) {
                this.logger.debug("Failed to refresh storage pool: " + e.getMessage());
            }

            try {
                vol = pool.storageVolLookupByName(volName);
                this.logger.debug("Found volume " + volName + " in storage pool " + pool.getName() + " after refreshing the pool");
            } catch (final LibvirtException e) {
                throw new CloudRuntimeException("Could not find volume " + volName + ": " + e.getMessage());
            }
        }

        return vol;
    }

    private void deleteDirVol(final StorageVol vol) throws LibvirtException {
        Script.runSimpleBashScript("rm -r --interactive=never " + vol.getPath());
    }

    private void deleteVol(final StorageVol vol) throws LibvirtException {
        vol.delete(0);
    }

    @Override
    public KvmPhysicalDisk createDiskFromTemplate(final KvmPhysicalDisk template, final String name, final PhysicalDiskFormat format,
                                                  final StorageProvisioningType provisioningType, final long
            size, final KvmStoragePool destPool, final int timeout) {

        this.logger.info(
                "Creating volume " + name + " from template " + template.getName() + " in pool " + destPool.getUuid()
                        + " (" + destPool.getType().toString() + ") with size " + size);

        KvmPhysicalDisk disk = null;

        if (destPool.getType() == StoragePoolType.RBD) {
            disk = createDiskFromTemplateOnRbd(template, name, size, destPool, timeout);
        } else {
            try {
                disk = destPool.createPhysicalDisk(name, format, provisioningType, template.getVirtualSize());
                if (disk == null) {
                    throw new CloudRuntimeException("Failed to create disk from template " + template.getName());
                }
                if (template.getFormat() == PhysicalDiskFormat.TAR) {
                    Script.runSimpleBashScript("tar -x -f " + template.getPath() + " -C " + disk.getPath(), timeout);
                } else if (template.getFormat() == PhysicalDiskFormat.DIR) {
                    Script.runSimpleBashScript("mkdir -p " + disk.getPath());
                    Script.runSimpleBashScript("chmod 755 " + disk.getPath());
                    Script.runSimpleBashScript("tar -x -f " + template.getPath() + "/*.tar -C " + disk.getPath(), timeout);
                } else if (format == PhysicalDiskFormat.QCOW2) {
                    final QemuImg qemu = new QemuImg(timeout);
                    final QemuImgFile destFile = new QemuImgFile(disk.getPath(), format);
                    if (size > template.getVirtualSize()) {
                        destFile.setSize(size);
                    } else {
                        destFile.setSize(template.getVirtualSize());
                    }
                    final Map<String, String> options = new HashMap<>();
                    options.put("preallocation", PreallocationType.getPreallocationType(provisioningType).toString().toLowerCase());
                    switch (provisioningType) {
                        case THIN:
                            final QemuImgFile backingFile = new QemuImgFile(template.getPath(), template.getFormat());
                            qemu.create(destFile, backingFile, options);
                            break;
                        case SPARSE:
                        case FAT:
                            final QemuImgFile srcFile = new QemuImgFile(template.getPath(), template.getFormat());
                            qemu.convert(srcFile, destFile, options);
                            break;
                        default:
                            break;
                    }
                } else if (format == PhysicalDiskFormat.RAW) {
                    final QemuImgFile sourceFile = new QemuImgFile(template.getPath(), template.getFormat());
                    final QemuImgFile destFile = new QemuImgFile(disk.getPath(), PhysicalDiskFormat.RAW);
                    if (size > template.getVirtualSize()) {
                        destFile.setSize(size);
                    } else {
                        destFile.setSize(template.getVirtualSize());
                    }
                    final QemuImg qemu = new QemuImg(timeout);
                    final Map<String, String> options = new HashMap<>();
                    qemu.convert(sourceFile, destFile, options);
                }
            } catch (final QemuImgException e) {
                this.logger.error("Failed to create " + disk.getPath() + " due to a failed executing of qemu-img: "
                        + e.getMessage());
            }
        }

        return disk;
    }

    private KvmPhysicalDisk createDiskFromTemplateOnRbd(final KvmPhysicalDisk template, final String name, final long size, final KvmStoragePool destPool, final int timeout) {

        /*
         * With RBD you can't run qemu-img convert with an existing RBD image as destination qemu-img will exit with the
         * error that the destination already exists. So for RBD we don't create the image, but let qemu-img do that for us.
         *
         * We then create a KVMPhysicalDisk object that we can return
         */

        final KvmStoragePool srcPool = template.getPool();
        KvmPhysicalDisk disk;

        final PhysicalDiskFormat format = PhysicalDiskFormat.RAW;
        disk = new KvmPhysicalDisk(destPool.getSourceDir() + "/" + name, name, destPool);
        disk.setFormat(format);
        if (size > template.getVirtualSize()) {
            disk.setSize(size);
            disk.setVirtualSize(size);
        } else {
            // leave these as they were if size isn't applicable
            disk.setSize(template.getVirtualSize());
            disk.setVirtualSize(disk.getSize());
        }

        final QemuImg qemu = new QemuImg(timeout);
        final QemuImgFile srcFile;
        final QemuImgFile destFile = new QemuImgFile(KvmPhysicalDisk.rbdStringBuilder(destPool.getSourceHost(),
                destPool.getSourcePort(),
                destPool.getAuthUserName(),
                destPool.getAuthSecret(),
                disk.getPath()));
        destFile.setFormat(format);

        if (srcPool.getType() != StoragePoolType.RBD) {
            srcFile = new QemuImgFile(template.getPath(), template.getFormat());
            try {
                qemu.convert(srcFile, destFile);
            } catch (final QemuImgException e) {
                this.logger.error("Failed to create " + disk.getPath()
                        + " due to a failed executing of qemu-img: " + e.getMessage());
            }
        } else {

            try {
                if (srcPool.getSourceHost().equals(destPool.getSourceHost())
                        && srcPool.getSourceDir().equals(destPool.getSourceDir())) {
                    /* We are on the same Ceph cluster, but we require RBD format 2 on the source image */
                    this.logger.debug("Trying to perform a RBD clone (layering) since we are operating in the same storage pool");

                    final Rados r = new Rados(srcPool.getAuthUserName());
                    r.confSet("mon_host", srcPool.getSourceHost() + ":" + srcPool.getSourcePort());
                    r.confSet("key", srcPool.getAuthSecret());
                    r.confSet("client_mount_timeout", "30");
                    r.connect();
                    this.logger.debug("Succesfully connected to Ceph cluster at " + r.confGet("mon_host"));

                    final IoCTX io = r.ioCtxCreate(srcPool.getSourceDir());
                    final Rbd rbd = new Rbd(io);
                    final RbdImage srcImage = rbd.open(template.getName());

                    if (srcImage.isOldFormat()) {
                        /* The source image is RBD format 1, we have to do a regular copy */
                        this.logger.debug("The source image " + srcPool.getSourceDir() + "/" + template.getName()
                                + " is RBD format 1. We have to perform a regular copy (" + disk.getVirtualSize() + " bytes)");

                        rbd.create(disk.getName(), disk.getVirtualSize(), this.rbdFeatures, this.rbdOrder);
                        final RbdImage destImage = rbd.open(disk.getName());

                        this.logger.debug("Starting to copy " + srcImage.getName() + " to " + destImage.getName() + " in Ceph pool "
                                + srcPool.getSourceDir());
                        rbd.copy(srcImage, destImage);

                        this.logger.debug("Finished copying " + srcImage.getName() + " to " + destImage.getName() + " in Ceph pool "
                                + srcPool.getSourceDir());
                        rbd.close(destImage);
                    } else {
                        final String rbdTemplateSnapName = "cloudstack-base-snap";
                        this.logger.debug("The source image " + srcPool.getSourceDir() + "/" + template.getName()
                                + " is RBD format 2. We will perform a RBD clone using snapshot "
                                + rbdTemplateSnapName);
                        /* The source image is format 2, we can do a RBD snapshot+clone (layering) */

                        this.logger.debug("Checking if RBD snapshot " + srcPool.getSourceDir() + "/" + template.getName()
                                + "@" + rbdTemplateSnapName + " exists prior to attempting a clone operation.");

                        final List<RbdSnapInfo> snaps = srcImage.snapList();
                        this.logger.debug("Found " + snaps.size() + " snapshots on RBD image " + srcPool.getSourceDir() + "/"
                                + template.getName());
                        boolean snapFound = false;
                        for (final RbdSnapInfo snap : snaps) {
                            if (rbdTemplateSnapName.equals(snap.name)) {
                                this.logger.debug("RBD snapshot " + srcPool.getSourceDir() + "/" + template.getName()
                                        + "@" + rbdTemplateSnapName + " already exists.");
                                snapFound = true;
                                break;
                            }
                        }

                        if (!snapFound) {
                            this.logger.debug("Creating RBD snapshot " + rbdTemplateSnapName + " on image " + name);
                            srcImage.snapCreate(rbdTemplateSnapName);
                            this.logger.debug("Protecting RBD snapshot " + rbdTemplateSnapName + " on image " + name);
                            srcImage.snapProtect(rbdTemplateSnapName);
                        }

                        rbd.clone(template.getName(), rbdTemplateSnapName, io, disk.getName(), this.rbdFeatures, this.rbdOrder);
                        this.logger.debug(
                                "Succesfully cloned " + template.getName() + "@" + rbdTemplateSnapName + " to " + disk.getName());
                        /* We also need to resize the image if the VM was deployed with a larger root disk size */
                        if (disk.getVirtualSize() > template.getVirtualSize()) {
                            final RbdImage diskImage = rbd.open(disk.getName());
                            diskImage.resize(disk.getVirtualSize());
                            rbd.close(diskImage);
                            this.logger.debug("Resized " + disk.getName() + " to " + disk.getVirtualSize());
                        }
                    }

                    rbd.close(srcImage);
                    r.ioCtxDestroy(io);
                } else {
                    /* The source pool or host is not the same Ceph cluster, we do a simple copy with Qemu-Img */
                    this.logger.debug("Both the source and destination are RBD, but not the same Ceph cluster. Performing a copy");

                    final Rados rSrc = new Rados(srcPool.getAuthUserName());
                    rSrc.confSet("mon_host", srcPool.getSourceHost() + ":" + srcPool.getSourcePort());
                    rSrc.confSet("key", srcPool.getAuthSecret());
                    rSrc.confSet("client_mount_timeout", "30");
                    rSrc.connect();
                    this.logger.debug("Succesfully connected to source Ceph cluster at " + rSrc.confGet("mon_host"));

                    final Rados rDest = new Rados(destPool.getAuthUserName());
                    rDest.confSet("mon_host", destPool.getSourceHost() + ":" + destPool.getSourcePort());
                    rDest.confSet("key", destPool.getAuthSecret());
                    rDest.confSet("client_mount_timeout", "30");
                    rDest.connect();
                    this.logger.debug("Succesfully connected to source Ceph cluster at " + rDest.confGet("mon_host"));

                    final IoCTX sourceIo = rSrc.ioCtxCreate(srcPool.getSourceDir());
                    final Rbd sourceRbd = new Rbd(sourceIo);

                    final IoCTX destinationIo = rDest.ioCtxCreate(destPool.getSourceDir());
                    final Rbd destinationRbd = new Rbd(destinationIo);

                    this.logger.debug(
                            "Creating " + disk.getName() + " on the destination cluster " + rDest.confGet("mon_host") + " in pool "
                                    + destPool.getSourceDir());

                    destinationRbd.create(disk.getName(), disk.getVirtualSize(), this.rbdFeatures, this.rbdOrder);

                    final RbdImage srcImage = sourceRbd.open(template.getName());
                    final RbdImage destImage = destinationRbd.open(disk.getName());

                    this.logger.debug("Copying " + template.getName() + " from Ceph cluster " + rSrc.confGet("mon_host") + " to "
                            + disk.getName()
                            + " on cluster " + rDest.confGet("mon_host"));
                    sourceRbd.copy(srcImage, destImage);

                    sourceRbd.close(srcImage);
                    destinationRbd.close(destImage);

                    rSrc.ioCtxDestroy(sourceIo);
                    rDest.ioCtxDestroy(destinationIo);
                }
            } catch (final RadosException e) {
                this.logger.error("Failed to perform a RADOS action on the Ceph cluster, the error was: " + e.getMessage());
                disk = null;
            } catch (final RbdException e) {
                this.logger.error("Failed to perform a RBD action on the Ceph cluster, the error was: " + e.getMessage());
                disk = null;
            }
        }
        return disk;
    }

    @Override
    public KvmPhysicalDisk createTemplateFromDisk(final KvmPhysicalDisk disk, final String name, final PhysicalDiskFormat format, final long size, final KvmStoragePool destPool) {
        return null;
    }

    @Override
    public List<KvmPhysicalDisk> listPhysicalDisks(final String storagePoolUuid, final KvmStoragePool pool) {
        final LibvirtStoragePool libvirtPool = (LibvirtStoragePool) pool;
        final StoragePool virtPool = libvirtPool.getPool();
        final List<KvmPhysicalDisk> disks = new ArrayList<>();
        try {
            final String[] vols = virtPool.listVolumes();
            for (final String volName : vols) {
                final KvmPhysicalDisk disk = getPhysicalDisk(volName, pool);
                disks.add(disk);
            }
            return disks;
        } catch (final LibvirtException e) {
            throw new CloudRuntimeException(e.toString());
        }
    }

    private LibvirtStorageVolumeDef getStorageVolumeDef(final StorageVol vol) throws LibvirtException {
        final String volDefXml = vol.getXMLDesc(0);
        final LibvirtStorageVolumeXmlParser parser = new LibvirtStorageVolumeXmlParser();
        return parser.parseStorageVolumeXml(volDefXml);
    }

    @Override
    public KvmPhysicalDisk copyPhysicalDisk(final KvmPhysicalDisk disk, final String name, final KvmStoragePool destPool, final int timeout) {

        final KvmStoragePool srcPool = disk.getPool();
        final PhysicalDiskFormat sourceFormat = disk.getFormat();
        final String sourcePath = disk.getPath();

        KvmPhysicalDisk newDisk;
        this.logger.debug("copyPhysicalDisk: disk size:" + disk.getSize() + ", virtualsize:" + disk.getVirtualSize()
                + " format:" + disk.getFormat());
        if (destPool.getType() != StoragePoolType.RBD) {
            if (disk.getFormat() == PhysicalDiskFormat.TAR) {
                newDisk = destPool.createPhysicalDisk(name, PhysicalDiskFormat.DIR, StorageProvisioningType.THIN,
                        disk.getVirtualSize());
            } else {
                newDisk = destPool.createPhysicalDisk(name, StorageProvisioningType.THIN, disk.getVirtualSize());
            }
        } else {
            newDisk = new KvmPhysicalDisk(destPool.getSourceDir() + "/" + name, name, destPool);
            newDisk.setFormat(PhysicalDiskFormat.RAW);
            newDisk.setSize(disk.getVirtualSize());
            newDisk.setVirtualSize(disk.getSize());
        }

        final String destPath = newDisk.getPath();
        final PhysicalDiskFormat destFormat = newDisk.getFormat();

        final QemuImg qemu = new QemuImg(timeout);
        QemuImgFile srcFile = null;
        QemuImgFile destFile = null;

        if (srcPool.getType() != StoragePoolType.RBD && destPool.getType() != StoragePoolType.RBD) {
            if (sourceFormat == PhysicalDiskFormat.TAR && destFormat == PhysicalDiskFormat.DIR) { // LXC template
                Script.runSimpleBashScript("cp " + sourcePath + " " + destPath);
            } else if (sourceFormat == PhysicalDiskFormat.TAR) {
                Script.runSimpleBashScript("tar -x -f " + sourcePath + " -C " + destPath, timeout);
            } else if (sourceFormat == PhysicalDiskFormat.DIR) {
                Script.runSimpleBashScript("mkdir -p " + destPath);
                Script.runSimpleBashScript("chmod 755 " + destPath);
                Script.runSimpleBashScript("cp -p -r " + sourcePath + "/* " + destPath, timeout);
            } else {
                srcFile = new QemuImgFile(sourcePath, sourceFormat);
                try {
                    final Map<String, String> info = qemu.info(srcFile);
                    final String backingFile = info.get("backing_file");
                    // qcow2 templates can just be copied into place
                    if (sourceFormat.equals(destFormat) && backingFile == null) {
                        final String result = Script.runSimpleBashScript("cp -f " + sourcePath + " " + destPath, timeout);
                        if (result != null) {
                            throw new CloudRuntimeException("Failed to create disk: " + result);
                        }
                    } else {
                        destFile = new QemuImgFile(destPath, destFormat);
                        try {
                            qemu.convert(srcFile, destFile);
                            final Map<String, String> destInfo = qemu.info(destFile);
                            final Long virtualSize = Long.parseLong(destInfo.get("virtual_size"));
                            newDisk.setVirtualSize(virtualSize);
                            newDisk.setSize(virtualSize);
                        } catch (final QemuImgException e) {
                            this.logger.error("Failed to convert " + srcFile.getFileName() + " to " + destFile.getFileName()
                                    + " the error was: " + e.getMessage());
                            newDisk = null;
                        }
                    }
                } catch (final QemuImgException e) {
                    this.logger.error(
                            "Failed to fetch the information of file " + srcFile.getFileName() + " the error was: " + e.getMessage());
                    newDisk = null;
                }
            }
        } else if (srcPool.getType() != StoragePoolType.RBD && destPool.getType() == StoragePoolType.RBD) {
            /**
             * Using qemu-img we copy the QCOW2 disk to RAW (on RBD) directly. To do so it's mandatory that librbd on the
             * system is at least 0.67.7 (Ceph Dumpling)
             */
            this.logger.debug("The source image is not RBD, but the destination is. We will convert into RBD format 2");
            try {
                srcFile = new QemuImgFile(sourcePath, sourceFormat);
                final String rbdDestPath = destPool.getSourceDir() + "/" + name;
                final String rbdDestFile = KvmPhysicalDisk.rbdStringBuilder(destPool.getSourceHost(),
                        destPool.getSourcePort(),
                        destPool.getAuthUserName(),
                        destPool.getAuthSecret(),
                        rbdDestPath);
                destFile = new QemuImgFile(rbdDestFile, destFormat);

                this.logger.debug("Starting copy from source image " + srcFile.getFileName() + " to RBD image " + rbdDestPath);
                qemu.convert(srcFile, destFile);
                this.logger.debug("Succesfully converted source image " + srcFile.getFileName() + " to RBD image " + rbdDestPath);

                /* We have to stat the RBD image to see how big it became afterwards */
                final Rados r = new Rados(destPool.getAuthUserName());
                r.confSet("mon_host", destPool.getSourceHost() + ":" + destPool.getSourcePort());
                r.confSet("key", destPool.getAuthSecret());
                r.confSet("client_mount_timeout", "30");
                r.connect();
                this.logger.debug("Succesfully connected to Ceph cluster at " + r.confGet("mon_host"));

                final IoCTX io = r.ioCtxCreate(destPool.getSourceDir());
                final Rbd rbd = new Rbd(io);

                final RbdImage image = rbd.open(name);
                final RbdImageInfo rbdInfo = image.stat();
                newDisk.setSize(rbdInfo.size);
                newDisk.setVirtualSize(rbdInfo.size);
                this.logger.debug("After copy the resulting RBD image " + rbdDestPath + " is " + rbdInfo.size + " bytes long");
                rbd.close(image);

                r.ioCtxDestroy(io);
            } catch (final QemuImgException e) {
                this.logger.error("Failed to convert from " + srcFile.getFileName() + " to " + destFile.getFileName()
                        + " the error was: " + e.getMessage());
                newDisk = null;
            } catch (final RadosException e) {
                this.logger.error("A Ceph RADOS operation failed (" + e.getReturnValue() + "). The error was: " + e.getMessage());
                newDisk = null;
            } catch (final RbdException e) {
                this.logger.error("A Ceph RBD operation failed (" + e.getReturnValue() + "). The error was: " + e.getMessage());
                newDisk = null;
            }
        } else {
            /**
             * We let Qemu-Img do the work here. Although we could work with librbd and have that do the cloning it doesn't
             * benefit us. It's better to keep the current code in place which works
             */
            srcFile = new QemuImgFile(KvmPhysicalDisk.rbdStringBuilder(srcPool.getSourceHost(), srcPool.getSourcePort(),
                    srcPool.getAuthUserName(), srcPool.getAuthSecret(),
                    sourcePath));
            srcFile.setFormat(sourceFormat);
            destFile = new QemuImgFile(destPath);
            destFile.setFormat(destFormat);

            try {
                qemu.convert(srcFile, destFile);
            } catch (final QemuImgException e) {
                this.logger.error("Failed to convert " + srcFile.getFileName() + " to " + destFile.getFileName()
                        + " the error was: " + e.getMessage());
                newDisk = null;
            }
        }

        if (newDisk == null) {
            throw new CloudRuntimeException("Failed to copy " + disk.getPath() + " to " + name);
        }

        return newDisk;
    }

    @Override
    public KvmPhysicalDisk createDiskFromSnapshot(final KvmPhysicalDisk snapshot, final String snapshotName, final String name, final KvmStoragePool destPool) {
        return null;
    }

    @Override
    public boolean refresh(final KvmStoragePool pool) {
        final LibvirtStoragePool libvirtPool = (LibvirtStoragePool) pool;
        final StoragePool virtPool = libvirtPool.getPool();
        try {
            refreshPool(virtPool);
        } catch (final LibvirtException e) {
            return false;
        }
        return true;
    }

    @Override
    public boolean createFolder(final String uuid, final String path) {
        final String mountPoint = this.mountPoint + File.separator + uuid;
        final File folder = new File(mountPoint + File.separator + path);
        if (!folder.exists()) {
            folder.mkdirs();
        }
        return true;
    }

    private void refreshPool(final StoragePool pool) throws LibvirtException {
        pool.refresh(0);
    }

    private LibvirtStoragePoolDef getStoragePoolDef(final StoragePool pool) throws LibvirtException {
        final String poolDefXml = pool.getXMLDesc(0);
        final LibvirtStoragePoolXmlParser parser = new LibvirtStoragePoolXmlParser();
        return parser.parseStoragePoolXml(poolDefXml);
    }
}
