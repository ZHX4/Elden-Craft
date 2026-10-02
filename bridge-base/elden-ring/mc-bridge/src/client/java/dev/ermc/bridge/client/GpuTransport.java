package dev.ermc.bridge.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import java.nio.MappedByteBuffer;
import java.nio.ByteOrder;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import org.slf4j.LoggerFactory;

/** Named D3D12 textures imported directly into the Minecraft OpenGL context. */
final class GpuTransport {
    static final int SLOTS=6;
    private static final VarHandle LONG=MethodHandles.byteBufferViewVarHandle(long[].class,ByteOrder.LITTLE_ENDIAN);
    private static final int[][] memory=new int[SLOTS][4], texture=new int[SLOTS][4], framebuffer=new int[SLOTS][4];
    private static final long[] used=new long[SLOTS];
    private static int generation, semaphore, width, height, shader, vao;
    private static int failedGeneration;
    private static boolean checked, supported;
    private static MappedByteBuffer header;
    static int generation() {return generation;}
    static void discard(int slot) {used[slot]=0;}
    static void published(int slot,long id) {LONG.setRelease(header,0xA0+slot*8,id);}
    static boolean available(int slot) {return used[slot]==0 || (long)LONG.getAcquire(header,0x60+slot*8)>=used[slot];}
    private static void checkError(String step) {
        int e=GL11.glGetError(); if(e!=0) throw new IllegalStateException(step+": GL error "+Integer.toHexString(e));
    }
    static boolean open(MappedByteBuffer shared, int w, int h) {
        if(!checked) {
            var c=GL.getCapabilities();
            supported=c.GL_EXT_memory_object_win32 && c.GL_EXT_semaphore_win32;
            checked=true; LoggerFactory.getLogger("erbridge").info("GPU sharing extensions supported: {}",supported);
        }
        if(!supported || shared.getInt(0x40)!=0x47504d43 || shared.getInt(0x44)!=w || shared.getInt(0x48)!=h || shared.getInt(0x54)!=SLOTS) return false;
        int next=shared.getInt(0x4c);
        if(next==failedGeneration) return false;
        if(next==generation) return true;
        destroy();
        while(GL11.glGetError()!=0) {}
        int oldTexture=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int oldFramebuffer=GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        try(MemoryStack stack=MemoryStack.stackPush()) {
            String prefix="Local\\ERMCGPU_"+Integer.toUnsignedString(shared.getInt(0x50))+"_"+Integer.toUnsignedString(next);
            for(int s=0;s<SLOTS;s++) for(int layer=0;layer<4;layer++) {
                memory[s][layer]=EXTMemoryObject.glCreateMemoryObjectsEXT();
                EXTMemoryObject.glMemoryObjectParameteriEXT(memory[s][layer],EXTMemoryObject.GL_DEDICATED_MEMORY_OBJECT_EXT,1);
                EXTMemoryObjectWin32.glImportMemoryWin32NameEXT(memory[s][layer],0,EXTMemoryObjectWin32.GL_HANDLE_TYPE_D3D12_RESOURCE_EXT,
                    MemoryUtil.memAddress(stack.UTF16(prefix+"_"+s+"_"+layer)));
                checkError("import texture");
                texture[s][layer]=GL11.glGenTextures();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture[s][layer]);
                EXTMemoryObject.glTexStorageMem2DEXT(GL11.GL_TEXTURE_2D,1,layer==1?GL30.GL_R32F:GL11.GL_RGBA8,w,h,memory[s][layer],0);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
                framebuffer[s][layer]=GL30.glGenFramebuffers();
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,framebuffer[s][layer]);
                GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,texture[s][layer],0);
                if(GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER)!=GL30.GL_FRAMEBUFFER_COMPLETE) throw new IllegalStateException("shared framebuffer incomplete");
                checkError("texture storage");
            }
            semaphore=EXTSemaphore.glGenSemaphoresEXT();
            EXTSemaphoreWin32.glImportSemaphoreWin32NameEXT(semaphore,EXTSemaphoreWin32.GL_HANDLE_TYPE_D3D12_FENCE_EXT,
                MemoryUtil.memAddress(stack.UTF16(prefix+"_ready")));
            checkError("import fence");
            if(shader==0) {
                int vertex=compile(GL20.GL_VERTEX_SHADER,"#version 150\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.0-1.0,0.0,1.0);}");
                int fragment=compile(GL20.GL_FRAGMENT_SHADER,"#version 150\nuniform sampler2D sourceDepth;out float value;void main(){value=texelFetch(sourceDepth,ivec2(gl_FragCoord.xy),0).r;}");
                shader=GL20.glCreateProgram();GL20.glAttachShader(shader,vertex);GL20.glAttachShader(shader,fragment);GL20.glLinkProgram(shader);
                GL20.glDeleteShader(vertex);GL20.glDeleteShader(fragment);
                if(GL20.glGetProgrami(shader,GL20.GL_LINK_STATUS)==0) throw new IllegalStateException(GL20.glGetProgramInfoLog(shader));
                vao=GL30.glGenVertexArrays();
            }
            generation=next; width=w;height=h;header=shared;
            LoggerFactory.getLogger("erbridge").info("GPU sharing active: {}x{}, no pixel readback",w,h);
            return true;
        } catch(Throwable error) {
            failedGeneration=next;destroy();LoggerFactory.getLogger("erbridge").warn("GPU sharing unavailable; using readback",error);return false;
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D,oldTexture);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,oldFramebuffer);
        }
    }
    private static int compile(int type,String source) {
        int s=GL20.glCreateShader(type);GL20.glShaderSource(s,source);GL20.glCompileShader(s);
        if(GL20.glGetShaderi(s,GL20.GL_COMPILE_STATUS)==0) throw new IllegalStateException(GL20.glGetShaderInfoLog(s));return s;
    }
    static void begin(int slot) {
        EXTSemaphore.glSemaphoreParameterui64EXT(semaphore,EXTSemaphoreWin32.GL_D3D12_FENCE_VALUE_EXT,used[slot]);
        EXTSemaphore.glWaitSemaphoreEXT(semaphore,new int[0],texture[slot],new int[]{EXTSemaphore.GL_LAYOUT_GENERAL_EXT,EXTSemaphore.GL_LAYOUT_GENERAL_EXT,EXTSemaphore.GL_LAYOUT_GENERAL_EXT,EXTSemaphore.GL_LAYOUT_GENERAL_EXT});
    }
    static void capture(RenderTarget main,int slot,int layer) {
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,framebuffer[slot][layer]);
        if(layer!=1) {
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,main.frameBufferId);
            GL30.glBlitFramebuffer(0,0,width,height,0,0,width,height,GL11.GL_COLOR_BUFFER_BIT,GL11.GL_NEAREST);
        } else {
            int program=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),oldVao=GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
            int active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);GL13.glActiveTexture(GL13.GL_TEXTURE0);
            int oldTex=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            boolean depth=GL11.glIsEnabled(GL11.GL_DEPTH_TEST), blend=GL11.glIsEnabled(GL11.GL_BLEND),scissor=GL11.glIsEnabled(GL11.GL_SCISSOR_TEST),cull=GL11.glIsEnabled(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_DEPTH_TEST);GL11.glDisable(GL11.GL_BLEND);GL11.glDisable(GL11.GL_SCISSOR_TEST);GL11.glDisable(GL11.GL_CULL_FACE);
            GL20.glUseProgram(shader);GL11.glBindTexture(GL11.GL_TEXTURE_2D,main.getDepthTextureId());GL20.glUniform1i(GL20.glGetUniformLocation(shader,"sourceDepth"),0);
            GL30.glBindVertexArray(vao);GL11.glDrawArrays(GL11.GL_TRIANGLES,0,3);
            GL30.glBindVertexArray(oldVao);GL20.glUseProgram(program);GL11.glBindTexture(GL11.GL_TEXTURE_2D,oldTex);GL13.glActiveTexture(active);
            if(depth)GL11.glEnable(GL11.GL_DEPTH_TEST);if(blend)GL11.glEnable(GL11.GL_BLEND);if(scissor)GL11.glEnable(GL11.GL_SCISSOR_TEST);if(cull)GL11.glEnable(GL11.GL_CULL_FACE);
        }
        main.bindWrite(false);
    }
    static void finish(int slot,long id) {
        EXTSemaphore.glSemaphoreParameterui64EXT(semaphore,EXTSemaphoreWin32.GL_D3D12_FENCE_VALUE_EXT,id);
        EXTSemaphore.glSignalSemaphoreEXT(semaphore,new int[0],texture[slot],new int[]{EXTSemaphore.GL_LAYOUT_GENERAL_EXT,EXTSemaphore.GL_LAYOUT_GENERAL_EXT,EXTSemaphore.GL_LAYOUT_GENERAL_EXT,EXTSemaphore.GL_LAYOUT_GENERAL_EXT});
        used[slot]=id;
    }
    private static void destroy() {
        for(int s=0;s<SLOTS;s++){used[s]=0;for(int i=0;i<4;i++){
            if(framebuffer[s][i]!=0)GL30.glDeleteFramebuffers(framebuffer[s][i]);
            if(texture[s][i]!=0)GL11.glDeleteTextures(texture[s][i]);
            if(memory[s][i]!=0)EXTMemoryObject.glDeleteMemoryObjectsEXT(memory[s][i]);
            framebuffer[s][i]=texture[s][i]=memory[s][i]=0;
        }}
        if(semaphore!=0)EXTSemaphore.glDeleteSemaphoresEXT(semaphore);semaphore=0;generation=0;
    }
}
