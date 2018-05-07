package com.cloud.storage.template;

import com.cloud.legacymodel.communication.answer.DownloadAnswer;
import com.cloud.legacymodel.communication.command.DownloadCommand;
import com.cloud.legacymodel.network.Proxy;
import com.cloud.legacymodel.storage.TemplateDownloadStatus;
import com.cloud.legacymodel.storage.TemplateProp;
import com.cloud.legacymodel.storage.VMTemplateStatus;
import com.cloud.model.enumeration.ImageFormat;
import com.cloud.storage.resource.SecondaryStorageResource;
import com.cloud.utils.component.Manager;

import java.util.Map;

public interface DownloadManager extends Manager {

    /**
     * Initiate download of a public template
     *
     * @param id                     unique id.
     * @param url                    the url from where to download from
     * @param accountId              the accountId of the iso owner (null if public iso)
     * @param descr                  description of the template
     * @param user                   username used for authentication to the server
     * @param password               password used for authentication to the server
     * @param maxDownloadSizeInBytes (optional) max download size for the template, in bytes.
     * @param resourceType           signifying the type of resource like template, volume etc.
     * @return job-id that can be used to interrogate the status of the download.
     */
    public String downloadPublicTemplate(long id, String url, String name, ImageFormat format, Long accountId, String descr, String cksum, String installPathPrefix,
                                         String templatePath, String userName, String passwd, long maxDownloadSizeInBytes, Proxy proxy, DownloadCommand.ResourceType resourceType);

    Map<String, Processor> getProcessors();

    /**
     * Get the status of a download job
     *
     * @param jobId job Id
     * @return status of the download job
     */
    public TemplateDownloadStatus getDownloadStatus(String jobId);

    /**
     * Get the status of a download job
     *
     * @param jobId job Id
     * @return status of the download job
     */
    public VMTemplateStatus getDownloadStatus2(String jobId);

    /**
     * Get the download percent of a download job
     *
     * @param jobId job Id
     * @return
     */
    public int getDownloadPct(String jobId);

    /**
     * Get the download error if any
     *
     * @param jobId job Id
     * @return
     */
    public String getDownloadError(String jobId);

    /**
     * Get the local path for the download
     * @param jobId job Id
     * @return public String getDownloadLocalPath(String jobId);
     */

    /**
     * Handle download commands from the management server
     *
     * @param cmd cmd from server
     * @return answer representing status of download.
     */
    public DownloadAnswer handleDownloadCommand(SecondaryStorageResource resource, DownloadCommand cmd);

    /**
     * /**
     *
     * @return list of template info for installed templates
     */
    public Map<String, TemplateProp> gatherTemplateInfo(String templateDir);

    /**
     * /**
     *
     * @return list of volume info for installed volumes
     */
    public Map<Long, TemplateProp> gatherVolumeInfo(String volumeDir);
}
