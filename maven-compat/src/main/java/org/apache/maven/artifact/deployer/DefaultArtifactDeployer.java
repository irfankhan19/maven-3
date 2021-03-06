package org.apache.maven.artifact.deployer;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.metadata.ArtifactMetadata;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.DefaultArtifactRepository;
import org.apache.maven.artifact.repository.metadata.RepositoryMetadataDeploymentException;
import org.apache.maven.artifact.repository.metadata.RepositoryMetadataManager;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.LegacySupport;
import org.apache.maven.repository.RepositorySystem;
import org.apache.maven.repository.legacy.TransferListenerAdapter;
import org.apache.maven.repository.legacy.WagonManager;
import org.apache.maven.repository.legacy.resolver.transform.ArtifactTransformationManager;
import org.apache.maven.wagon.TransferFailedException;
import org.apache.maven.wagon.events.TransferListener;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.logging.AbstractLogEnabled;
import org.codehaus.plexus.util.FileUtils;

@Component( role = ArtifactDeployer.class )
public class DefaultArtifactDeployer
    extends AbstractLogEnabled
    implements ArtifactDeployer
{
    @Requirement
    private WagonManager wagonManager;

    @Requirement
    private ArtifactTransformationManager transformationManager;

    @Requirement
    private RepositoryMetadataManager repositoryMetadataManager;

    @Requirement
    private RepositorySystem repositorySystem;

    @Requirement
    private LegacySupport legacySupport;

    /**
     * @deprecated we want to use the artifact method only, and ensure artifact.file is set
     *             correctly.
     */
    @Deprecated
    public void deploy( String basedir, String finalName, Artifact artifact, ArtifactRepository deploymentRepository,
                        ArtifactRepository localRepository )
        throws ArtifactDeploymentException
    {
        String extension = artifact.getArtifactHandler().getExtension();
        File source = new File( basedir, finalName + "." + extension );
        deploy( source, artifact, deploymentRepository, localRepository );
    }

    public void deploy( File source, Artifact artifact, ArtifactRepository deploymentRepository,
                        ArtifactRepository localRepository )
        throws ArtifactDeploymentException
    {
        deploymentRepository = injectSession( deploymentRepository );

        try
        {
            transformationManager.transformForDeployment( artifact, deploymentRepository, localRepository );

            // Copy the original file to the new one if it was transformed
            File artifactFile = new File( localRepository.getBasedir(), localRepository.pathOf( artifact ) );
            if ( !artifactFile.equals( source ) )
            {
                FileUtils.copyFile( source, artifactFile );
            }

            wagonManager.putArtifact( source, artifact, deploymentRepository, getTransferListener() );

            // must be after the artifact is installed
            for ( ArtifactMetadata metadata : artifact.getMetadataList() )
            {
                repositoryMetadataManager.deploy( metadata, localRepository, deploymentRepository );
            }
        }
        catch ( TransferFailedException e )
        {
            throw new ArtifactDeploymentException( "Error deploying artifact: " + e.getMessage(), e );
        }
        catch ( IOException e )
        {
            throw new ArtifactDeploymentException( "Error deploying artifact: " + e.getMessage(), e );
        }
        catch ( RepositoryMetadataDeploymentException e )
        {
            throw new ArtifactDeploymentException( "Error installing artifact's metadata: " + e.getMessage(), e );
        }
    }

    private TransferListener getTransferListener()
    {
        MavenSession session = legacySupport.getSession();

        if ( session == null )
        {
            return null;
        }

        return TransferListenerAdapter.newAdapter( session.getRequest().getTransferListener() );
    }

    private ArtifactRepository injectSession( ArtifactRepository repository )
    {
        /*
         * NOTE: This provides backward-compat with maven-deploy-plugin:2.4 which bypasses the repository factory when
         * using an alternative deployment location.
         */
        if ( repository instanceof DefaultArtifactRepository && repository.getAuthentication() == null )
        {
            MavenSession session = legacySupport.getSession();

            if ( session != null )
            {
                MavenExecutionRequest request = session.getRequest();

                if ( request != null )
                {
                    List<ArtifactRepository> repositories = Arrays.asList( repository );

                    repositorySystem.injectProxy( repositories, request.getProxies() );

                    repositorySystem.injectAuthentication( repositories, request.getServers() );
                }
            }
        }

        return repository;
    }

}
