package me.piitex.engine.render3d.gl;

import me.piitex.engine.render3d.Pipeline;
import me.piitex.engine.render3d.PipelineDesc;
import me.piitex.engine.render3d.ShaderCompiler;

import static org.lwjgl.opengl.GL20C.*;
import static org.lwjgl.opengl.GL31C.glGetUniformBlockIndex;
import static org.lwjgl.opengl.GL31C.glUniformBlockBinding;
import static org.lwjgl.opengl.GL31C.GL_INVALID_INDEX;

final class GlPipeline implements Pipeline {
    final PipelineDesc desc;
    int program;

    GlPipeline(PipelineDesc desc) {
        this.desc = desc;
        ShaderCompiler.Compiled compiled = ShaderCompiler.compile(desc);
        try {
            int vertex = compileStage(GL_VERTEX_SHADER, compiled.vertexGlsl(), "vertex");
            int fragment;
            try {
                fragment = compileStage(GL_FRAGMENT_SHADER, compiled.fragmentGlsl(), "fragment");
            } catch (RuntimeException e) {
                glDeleteShader(vertex);
                throw e;
            }
            program = glCreateProgram();
            glAttachShader(program, vertex);
            glAttachShader(program, fragment);
            glLinkProgram(program);
            glDeleteShader(vertex);
            glDeleteShader(fragment);
            if (glGetProgrami(program, GL_LINK_STATUS) == 0) {
                String log = glGetProgramInfoLog(program);
                glDeleteProgram(program);
                program = 0;
                throw new IllegalStateException("The shaders do not link:\n" + log);
            }
            glUseProgram(program);
            for (ShaderCompiler.Binding binding : compiled.bindings) {
                if (binding.sampler()) {
                    int location = glGetUniformLocation(program, binding.glName());
                    if (location >= 0) glUniform1i(location, binding.binding());
                } else {
                    int index = glGetUniformBlockIndex(program, binding.glName());
                    if (index != GL_INVALID_INDEX) glUniformBlockBinding(program, index, binding.binding());
                }
            }
            glUseProgram(0);
        } finally {
            ShaderCompiler.free(compiled.vertexSpirv);
            ShaderCompiler.free(compiled.fragmentSpirv);
        }
    }

    private static int compileStage(int type, String source, String name) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == 0) {
            String log = glGetShaderInfoLog(shader);
            glDeleteShader(shader);
            throw new IllegalStateException("The " + name + " shader does not compile for OpenGL:\n" + log
                    + "\n--- generated GLSL ---\n" + source);
        }
        return shader;
    }

    @Override
    public void close() {
        if (program != 0) glDeleteProgram(program);
        program = 0;
    }
}
