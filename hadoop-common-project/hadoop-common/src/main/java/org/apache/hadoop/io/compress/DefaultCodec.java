/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.io.compress;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import log_events.org.apache.hadoop.IoNamespace;
import org.ngmon.logger.core.SimpleLogger;
import org.ngmon.logger.logtranslator.LogGlobal;
import org.ngmon.logger.core.LoggerFactory;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configurable;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.compress.zlib.ZlibDecompressor;
import org.apache.hadoop.io.compress.zlib.ZlibFactory;

@InterfaceAudience.Public
@InterfaceStability.Evolving
public class DefaultCodec implements Configurable, CompressionCodec, DirectDecompressionCodec {
  private static final /* LogLOG=LogFactory.getLog(DefaultCodec.class); */
			IoNamespace LOG = LoggerFactory.getLogger(IoNamespace.class, new SimpleLogger());
  
  Configuration conf;

  @Override
  public void setConf(Configuration conf) {
    this.conf = conf;
  }
  
  @Override
  public Configuration getConf() {
    return conf;
  }
  
  @Override
  public CompressionOutputStream createOutputStream(OutputStream out) 
  throws IOException {
    // This may leak memory if called in a loop. The createCompressor() call
    // may cause allocation of an untracked direct-backed buffer if native
    // libs are being used (even if you close the stream).  A Compressor
    // object should be reused between successive calls.
    /* LOG.warn("DefaultCodec.createOutputStream() may leak memory. "+"Create a compressor first.") */
    LOG.defaultcodec_createoutputstream_may_leak().warn();
    return new CompressorStream(out, createCompressor(), 
                                conf.getInt("io.file.buffer.size", 4*1024));
  }

  @Override
  public CompressionOutputStream createOutputStream(OutputStream out, 
                                                    Compressor compressor) 
  throws IOException {
    return new CompressorStream(out, compressor, 
                                conf.getInt("io.file.buffer.size", 4*1024));
  }

  @Override
  public Class<? extends Compressor> getCompressorType() {
    return ZlibFactory.getZlibCompressorType(conf);
  }

  @Override
  public Compressor createCompressor() {
    return ZlibFactory.getZlibCompressor(conf);
  }

  @Override
  public CompressionInputStream createInputStream(InputStream in) 
  throws IOException {
    return new DecompressorStream(in, createDecompressor(),
                                  conf.getInt("io.file.buffer.size", 4*1024));
  }

  @Override
  public CompressionInputStream createInputStream(InputStream in, 
                                                  Decompressor decompressor) 
  throws IOException {
    return new DecompressorStream(in, decompressor, 
                                  conf.getInt("io.file.buffer.size", 4*1024));
  }

  @Override
  public Class<? extends Decompressor> getDecompressorType() {
    return ZlibFactory.getZlibDecompressorType(conf);
  }

  @Override
  public Decompressor createDecompressor() {
    return ZlibFactory.getZlibDecompressor(conf);
  }
  
  /**
   * {@inheritDoc}
   */
  @Override
  public DirectDecompressor createDirectDecompressor() {
    return ZlibFactory.getZlibDirectDecompressor(conf);
  }
  
  
  @Override
  public String getDefaultExtension() {
    return ".deflate";
  }

}
