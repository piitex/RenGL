package me.piitex.engine.render3d.gl;

import me.piitex.engine.render3d.GpuTexture;
import org.lwjgl.opengl.EXTTextureFilterAnisotropic;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL12C.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL13C.*;
import static org.lwjgl.opengl.GL30C.glGenerateMipmap;

final class GlTexture implements GpuTexture {
    private final int width, height, mipLevels;
    /**
     * {@code GL_TEXTURE_2D} or {@code GL_TEXTURE_CUBE_MAP}.
     */
    final int target;
    private final boolean owned;
    int id;

    /**
     * A 2D texture with repeat wrapping.
     */
    GlTexture(int width, int height, ByteBuffer rgba, boolean mipmaps, float anisotropy) {
        if (rgba.remaining() < width * height * 4) throw new IllegalArgumentException("Not enough pixel data");
        this.width = width;
        this.height = height;
        this.target = GL_TEXTURE_2D;
        this.owned = true;
        this.mipLevels = mipmaps ? 32 - Integer.numberOfLeadingZeros(Math.max(width, height)) : 1;
        id = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, id);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
        if (mipmaps) glGenerateMipmap(GL_TEXTURE_2D);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, mipmaps ? GL_LINEAR_MIPMAP_LINEAR : GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
        if (mipmaps) applyAnisotropy(GL_TEXTURE_2D, anisotropy);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    /**
     * A 2D texture of 16-bit floats, with repeat wrapping.
     */
    GlTexture(int width, int height, java.nio.FloatBuffer rgba, boolean mipmaps, float anisotropy) {
        if (rgba.remaining() < width * height * 4) throw new IllegalArgumentException("Not enough pixel data");
        this.width = width;
        this.height = height;
        this.target = GL_TEXTURE_2D;
        this.owned = true;
        this.mipLevels = mipmaps ? 32 - Integer.numberOfLeadingZeros(Math.max(width, height)) : 1;
        id = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, id);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
        glTexImage2D(GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL30C.GL_RGBA16F, width, height, 0, GL_RGBA, GL_FLOAT, rgba);
        if (mipmaps) glGenerateMipmap(GL_TEXTURE_2D);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, mipmaps ? GL_LINEAR_MIPMAP_LINEAR : GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
        if (mipmaps) applyAnisotropy(GL_TEXTURE_2D, anisotropy);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    /**
     * A cube texture with edge clamping.
     */
    GlTexture(int size, ByteBuffer[] faces, boolean mipmaps, float anisotropy) {
        if (faces.length != 6) throw new IllegalArgumentException("A cube texture needs six faces");
        this.width = size;
        this.height = size;
        this.target = GL_TEXTURE_CUBE_MAP;
        this.owned = true;
        this.mipLevels = mipmaps ? 32 - Integer.numberOfLeadingZeros(size) : 1;
        id = glGenTextures();
        glBindTexture(GL_TEXTURE_CUBE_MAP, id);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
        for (int face = 0; face < 6; face++) {
            if (faces[face].remaining() < size * size * 4) throw new IllegalArgumentException("Not enough pixel data in face " + face);
            glTexImage2D(GL_TEXTURE_CUBE_MAP_POSITIVE_X + face, 0, GL_RGBA8, size, size, 0, GL_RGBA, GL_UNSIGNED_BYTE, faces[face]);
        }
        if (mipmaps) glGenerateMipmap(GL_TEXTURE_CUBE_MAP);
        glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_MIN_FILTER, mipmaps ? GL_LINEAR_MIPMAP_LINEAR : GL_LINEAR);
        glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_CUBE_MAP, org.lwjgl.opengl.GL12C.GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);
        if (mipmaps) applyAnisotropy(GL_TEXTURE_CUBE_MAP, anisotropy);
        glBindTexture(GL_TEXTURE_CUBE_MAP, 0);
    }

    /**
     * Another name for a texture someone else owns, such as a render target's colour image. Closing it frees nothing.
     */
    GlTexture(int existing, int width, int height) {
        this.width = width;
        this.height = height;
        this.target = GL_TEXTURE_2D;
        this.owned = false;
        this.mipLevels = 1;
        this.id = existing;
    }

    static void applyAnisotropy(int target, float level) {
        if (level > 1f && GL.getCapabilities().GL_EXT_texture_filter_anisotropic) {
            glTexParameterf(target, EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT, level);
        }
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public int mipLevels() {
        return mipLevels;
    }

    @Override
    public void close() {
        if (owned && id != 0) glDeleteTextures(id);
        id = 0;
    }
}
